import numpy as np

from app.main import normalize_image


def test_normalize_image_returns_contiguous_uint8_rgb_for_grayscale():
    source = np.zeros((40, 60), dtype=np.uint16)

    result = normalize_image(source)

    assert result.shape == (40, 60, 3)
    assert result.dtype == np.uint8
    assert result.flags.c_contiguous


def test_normalize_image_drops_alpha_channel():
    source = np.zeros((40, 60, 4), dtype=np.uint8)

    result = normalize_image(source)

    assert result.shape == (40, 60, 3)
