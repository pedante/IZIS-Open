import json
import sys

import pytest

import katrain.core.constants as constants
from izis_katrain.launch import configure


def test_profile_is_isolated_preserved_and_size_checked(tmp_path, monkeypatch):
    monkeypatch.setattr(sys, "argv", ["proof"])
    monkeypatch.setattr(constants, "DATA_FOLDER", constants.DATA_FOLDER)
    for name in ("KIVY_HOME", "KIVY_NO_ARGS", "KIVY_NO_CONSOLELOG"):
        monkeypatch.setenv(name, "test")
    runtime = configure(tmp_path, tmp_path / "katago", 9, 40)
    config = runtime / "config.json"
    original = json.loads(config.read_text())
    assert original["game"]["size"] == 9
    assert original["engine"]["max_visits"] == 40
    (runtime / "analysis.cfg").write_text("numAnalysisThreads = 2\n")
    configure(tmp_path, tmp_path / "katago", 9, 10)
    assert json.loads(config.read_text()) == original
    assert (runtime / "analysis.cfg").read_text() == "numAnalysisThreads = 2\n"
    with pytest.raises(ValueError, match="Saved board size"):
        configure(tmp_path, tmp_path / "katago", 19, 10)
    assert json.loads(config.read_text()) == original
