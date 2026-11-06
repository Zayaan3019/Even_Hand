"""Flattens the CityU Operating Systems grading files into one CSV for the validation.

Usage: cityu-to-csv.py DIR OUT.csv
"""
import csv
import json
import sys
from pathlib import Path

root, out = Path(sys.argv[1]), sys.argv[2]
FIELDS = ["full_points", "sample_answer", "answer", "score_1", "score_2", "score_3"]
with open(out, "w", newline="", encoding="utf-8") as f:
    w = csv.writer(f)
    w.writerow(["question"] + FIELDS)
    for q in range(1, 7):
        data = json.loads((root / f"q{q}" / "grading.json").read_bytes().decode("utf-8", "replace"))
        for student in data.values():
            for part in student.values():
                # Question 6 was published with only two of the three TAs' marks, so it cannot be used here.
                if all(k in part for k in FIELDS):
                    w.writerow([q] + [part[k] for k in FIELDS])
