"""The test-tool dependencies are installed and work headlessly."""

import matplotlib

matplotlib.use("Agg")

import cv2  # noqa: E402
import numpy as np  # noqa: E402
import scipy  # noqa: E402
import skimage  # noqa: E402
from matplotlib import pyplot as plt  # noqa: E402


def test_opencv_draws_and_encodes():
    img = np.zeros((100, 100, 3), np.uint8)
    cv2.circle(img, (50, 50), 20, (0, 255, 255), -1)
    ok, buf = cv2.imencode(".png", img)
    assert ok and len(buf) > 0


def test_matplotlib_renders_without_a_display(tmp_path):
    fig, ax = plt.subplots()
    ax.plot([0, 1], [0, 1])
    out = tmp_path / "chart.png"
    fig.savefig(out)
    plt.close(fig)
    assert out.stat().st_size > 0


def test_scientific_stack_imports():
    assert scipy.__version__ and skimage.__version__
