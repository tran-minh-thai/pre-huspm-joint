# -*- coding: utf-8 -*-
"""Build a SELF-CONTAINED Colab notebook (make_figures_colab.ipynb) from
make_figures.py. Keeps make_figures.py as the single source of truth: re-run
this whenever the script changes to regenerate the notebook.

  python3 analysis/build_colab_notebook.py
"""
import json, os, re

HERE = os.path.dirname(os.path.abspath(__file__))
SRC  = os.path.join(HERE, "make_figures.py")
OUT  = os.path.join(HERE, "make_figures_colab.ipynb")

# Drive path = local "My Drive/..." mapped to Colab "/content/drive/MyDrive/..."
DRIVE_PROJECT = "/content/drive/MyDrive/Researching/pre-huspm-adaptive"


def code_cell(src):
    return {"cell_type": "code", "metadata": {}, "execution_count": None,
            "outputs": [], "source": src.rstrip("\n").splitlines(keepends=True)}


def md_cell(src):
    return {"cell_type": "markdown", "metadata": {},
            "source": src.rstrip("\n").splitlines(keepends=True)}


def main():
    text = open(SRC, encoding="utf-8").read()
    # drop the "if __name__ == '__main__': main()" trailer (we add an explicit run cell)
    text = re.sub(r"\nif __name__ == .__main__.:\n\s*main\(\)\s*$", "\n", text)
    # split into cells on the "# %%" markers (keep the marker line with its cell)
    parts, buf = [], []
    for line in text.splitlines(keepends=True):
        if line.lstrip().startswith("# %%") and buf:
            parts.append("".join(buf)); buf = [line]
        else:
            buf.append(line)
    if buf:
        parts.append("".join(buf))

    intro = md_cell(
        "# HUSP experiment - figures & tables (Colab)\n\n"
        "Sinh **hình PDF vector** (IEEE/ACM, DPI 300) + **bảng tổng hợp** từ các CSV\n"
        "`SC1..SC7` (31 cột, gồm `explored_nodes`).\n\n"
        "**Cách dùng:** ▶ chạy lần lượt từ trên xuống (hoặc *Runtime → Run all*).\n"
        "1. Cell **Setup**: mount Google Drive + **sửa `PROJECT`** trỏ tới thư mục\n"
        "   `pre-huspm-adaptive` của bạn trên Drive.\n"
        "2. Các cell tiếp theo định nghĩa pipeline.\n"
        "3. Cell cuối `main()` sinh toàn bộ hình + bảng vào `.../analysis/figures/`.\n\n"
        "Phân biệt rõ trạng thái **OK / OT / OOM / Fail / Skip** trên hình & bảng.")

    setup = code_cell(
        "# === SETUP (Colab) =========================================================\n"
        "from google.colab import drive\n"
        "drive.mount('/content/drive')\n"
        "import os\n\n"
        "# >>> SỬA đường dẫn này cho khớp thư mục pre-huspm-adaptive trên Drive của bạn <<<\n"
        f"PROJECT = \"{DRIVE_PROJECT}\"\n\n"
        "os.environ['HUSP_DATA_DIR'] = os.path.join(PROJECT, 'results')\n"
        "os.environ['HUSP_OUT_DIR']  = os.path.join(PROJECT, 'analysis', 'figures')\n"
        "assert os.path.isdir(os.environ['HUSP_DATA_DIR']), \\\n"
        "    'Khong thay results/ - kiem tra lai PROJECT: ' + os.environ['HUSP_DATA_DIR']\n\n"
        "# seaborn + jinja2 co san tren Colab; cai phong neu thieu\n"
        "try:\n"
        "    import seaborn  # noqa\n"
        "except Exception:\n"
        "    import subprocess, sys\n"
        "    subprocess.run([sys.executable, '-m', 'pip', '-q', 'install', 'seaborn'])\n"
        "print('DATA_DIR =', os.environ['HUSP_DATA_DIR'])")

    run = code_cell(
        "# === RUN: sinh toan bo hinh + bang =========================================\n"
        "main()\n\n"
        "# liet ke output\n"
        "import os\n"
        "out = os.environ['HUSP_OUT_DIR']\n"
        "print('\\nOutputs in', out)\n"
        "for f in sorted(os.listdir(out)):\n"
        "    print('  ', f)")

    cells = [intro, setup] + [code_cell(p) for p in parts] + [run]
    nb = {"cells": cells,
          "metadata": {"colab": {"provenance": []},
                       "kernelspec": {"name": "python3", "display_name": "Python 3"},
                       "language_info": {"name": "python"}},
          "nbformat": 4, "nbformat_minor": 0}
    with open(OUT, "w", encoding="utf-8") as fh:
        json.dump(nb, fh, ensure_ascii=False, indent=1)
    print(f"[built] {OUT}  ({len(cells)} cells)")


if __name__ == "__main__":
    main()
