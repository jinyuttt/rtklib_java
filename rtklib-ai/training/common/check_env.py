"""训练环境依赖自检：缺失或版本过低时，直接给出可执行的安装命令。

用法（在 ``rtklib-ai`` 目录下执行）::

    python -m training.common.check_env
"""

from __future__ import annotations

import importlib
import sys

REQUIRED = (
    ("numpy", "numpy", "1.24"),
    ("pyarrow", "pyarrow", "14.0"),
    ("onnx", "onnx", "1.15"),
    ("onnxruntime", "onnxruntime", "1.17"),
    ("torch", "torch", "2.0"),
)


def _version_tuple(text: str) -> tuple:
    parts = []
    for piece in str(text).split("."):
        digits = "".join(ch for ch in piece if ch.isdigit())
        parts.append(int(digits) if digits else 0)
    return tuple(parts)


def check() -> int:
    print(f"Python {sys.version.split()[0]}")
    problems = []
    for module, pip_name, minimum in REQUIRED:
        try:
            mod = importlib.import_module(module)
        except ImportError:
            print(f"  [缺失] {module}（需 >= {minimum}）")
            problems.append((pip_name, minimum))
            continue
        version = getattr(mod, "__version__", "0")
        if _version_tuple(version) >= _version_tuple(minimum):
            print(f"  [通过] {module} {version}（需 >= {minimum}）")
        else:
            print(f"  [过低] {module} {version}（需 >= {minimum}）")
            problems.append((pip_name, minimum))

    if problems:
        specs = " ".join(f'"{name}>={ver}"' for name, ver in problems)
        print("\n依赖不完整。安装命令：")
        print(f"  python -m pip install {specs}")
        print("  或按清单安装：python -m pip install -r training/requirements.txt")
        return 1

    print("\n依赖齐全，可执行 python -m training.obs_corrector.train --demo")
    return 0


if __name__ == "__main__":
    raise SystemExit(check())