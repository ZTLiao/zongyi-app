# -*- coding: utf-8 -*-
"""
jsoup 重定位：org.jsoup -> com.zongyi.jsoup
原因：部分 TV/盒子（如 TCL）把旧版 jsoup 打进系统 framework
(/system/framework/com.tcl.tclvoicecontrol.jar)，boot classpath 优先级
高于应用 dex，应用内的 org.jsoup 类被系统旧版遮蔽，selectFirst 等
新版方法 NoSuchMethodError。

做法：正确解析 class 文件常量池，改写 UTF8 常量条目（内部名
org/jsoup/...、描述符 Lorg/jsoup/...、反射字符串 org.jsoup...），
重建 jar。jarjar 不可用时的等价实现。
"""
import struct, sys, zipfile, io, os

SRC = r"C:\Users\pc\.gradle\caches\modules-2\files-2.1\org.jsoup\jsoup\1.18.3\fb42d6cc9898ced4b73175eac49c61a40f35325\jsoup-1.18.3.jar"
DST = r"C:\projects\zongyi-app\app\libs\jsoup-relocated.jar"

OLD_SLASH = b"org/jsoup"
NEW_SLASH = b"com/zongyi/jsoup"
OLD_DOT = b"org.jsoup"
NEW_DOT = b"com.zongyi.jsoup"

def rewrite_utf8_content(content: bytes) -> bytes:
    content = content.replace(OLD_SLASH, NEW_SLASH)
    content = content.replace(OLD_DOT, NEW_DOT)
    return content

def rewrite_class(data: bytes) -> bytes:
    if data[:4] != b"\xca\xfe\xba\xbe":
        raise ValueError("not a class file")
    cp_count = struct.unpack(">H", data[8:10])[0]  # 常量池数量 = 实际数量+1
    i = 10
    idx = 1
    # 两遍法：一遍在【原始数据】上解析并收集替换区（起止偏移按原布局记录），
    # 末尾统一拼接——避免解析位置与新长度错位
    reps = []  # (start, end, new_bytes)
    while idx < cp_count:
        tag = data[i]
        if tag == 1:  # CONSTANT_Utf8
            ln = struct.unpack(">H", data[i+1:i+3])[0]
            content = data[i+3:i+3+ln]
            new = rewrite_utf8_content(content)
            if new != content:
                seg = bytes([tag]) + struct.pack(">H", len(new)) + new
                reps.append((i, i + 3 + ln, seg))
            i += 3 + ln
        elif tag in (7, 8, 16, 19, 20):  # Class/String/MethodType/Module/Package: u1+u2
            i += 3
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):  # Integer/Float/refs/NameAndType/Dynamic/InvokeDynamic: u1+u4
            i += 5
        elif tag in (5, 6):  # Long/Double: u1+u8, 占两个槽
            i += 9
            idx += 1
        elif tag == 15:  # MethodHandle: u1 tag + u1 reference_kind + u2 reference_index = 4 字节
            i += 4
        else:
            raise ValueError("unknown cp tag %d" % tag)
        idx += 1
    if not reps:
        return bytes(data)
    out = bytearray()
    pos = 0
    for start, end, seg in reps:
        out += data[pos:start]
        out += seg
        pos = end
    out += data[pos:]
    return bytes(out)

def main():
    os.makedirs(os.path.dirname(DST), exist_ok=True)
    zin = zipfile.ZipFile(SRC, "r")
    zout = zipfile.ZipFile(DST, "w", zipfile.ZIP_DEFLATED)
    class_count = 0
    for info in zin.infolist():
        name = info.filename
        data = zin.read(name)
        base = name.rsplit("/", 1)[-1]
        # 跳过：多版本私有 module 信息、签名文件（重写后签名失效）
        if name == "module-info.class" or name.startswith("META-INF/versions/"):
            continue
        if base.endswith((".SF", ".RSA", ".DSA", ".EC")):
            continue
        if name.endswith(".class"):
            data = rewrite_class(data)
            class_count += 1
        # zip 条目路径同步重命名（org/jsoup/... -> com/zongyi/jsoup/...）
        new_name = name.replace("org/jsoup", "com/zongyi/jsoup").replace("org.jsoup", "com.zongyi.jsoup")
        zout.writestr(new_name, data)
    zout.close(); zin.close()
    print("rewrote %d classes -> %s" % (class_count, DST))

    # 校验：产物内不得残留 org/jsoup / org.jsoup
    zin = zipfile.ZipFile(DST, "r")
    leftover = 0
    for name in zin.namelist():
        if name.endswith(".class") and (b"org/jsoup" in zin.read(name) or b"org.jsoup" in zin.read(name)):
            leftover += 1
            print("LEFTOVER:", name)
    print("leftover=%d" % leftover)
    assert leftover == 0

if __name__ == "__main__":
    main()
