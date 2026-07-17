#!/bin/bash
# 16 KB page size 对齐：直接修改 ELF 程序头中 LOAD 段的 p_align 字段
# Google Play 要求 2025-11 起 target Android 15+ 的应用必须支持

set -euo pipefail

NDK_DIR="$1"
BUILD_DIR="$2"

if [ -z "$NDK_DIR" ] || [ -z "$BUILD_DIR" ]; then
    echo "Usage: $0 <ndk-dir> <build-dir>"
    exit 1
fi

# 遍历所有 mergeNativeLibs 输出目录
for VARIANT_DIR in "$BUILD_DIR"/intermediates/merged_native_libs/*/*/out; do
    [ -d "$VARIANT_DIR" ] || continue
    ARM_DIR="$VARIANT_DIR/lib/arm64-v8a"
    [ -d "$ARM_DIR" ] || continue
    echo "Patching p_align in $ARM_DIR"

    for SO in "$ARM_DIR"/*.so; do
        [ -f "$SO" ] || continue

        # 读取 ELF 64 位程序头信息
        # e_phoff: offset 32, 8 bytes LE
        PHOFF=$(od -An -t u8 -j 32 -N 8 "$SO" | tr -d ' ')
        # e_phentsize: offset 54, 2 bytes LE
        PHENTSIZE=$(od -An -t u2 -j 54 -N 2 "$SO" | tr -d ' ')
        # e_phnum: offset 56, 2 bytes LE
        PHNUM=$(od -An -t u2 -j 56 -N 2 "$SO" | tr -d ' ')

        PATCHED=0
        for ((i=0; i<PHNUM; i++)); do
            ENTRY_OFF=$((PHOFF + i * PHENTSIZE))
            # p_type: offset 0 within entry, 4 bytes LE
            PTYPE=$(od -An -t u4 -j $ENTRY_OFF -N 4 "$SO" | tr -d ' ')
            if [ "$PTYPE" = "1" ]; then
                # p_align: offset 48 within entry, 8 bytes LE
                ALIGN_OFF=$((ENTRY_OFF + 48))
                CUR_ALIGN=$(od -An -t u8 -j $ALIGN_OFF -N 8 "$SO" | tr -d ' ')
                if [ "$CUR_ALIGN" -lt 16384 ]; then
                    # 修改 p_align = 0x4000 (16384)
                    printf '\x00\x40\x00\x00\x00\x00\x00\x00' | dd of="$SO" bs=1 seek=$ALIGN_OFF count=8 conv=notrunc 2>/dev/null
                    PATCHED=$((PATCHED + 1))
                fi
            fi
        done

        if [ "$PATCHED" -gt 0 ]; then
            echo "  OK $(basename "$SO") ($PATCHED LOAD segments patched)"
        else
            echo "  -- already aligned: $(basename "$SO")"
        fi
    done
done
