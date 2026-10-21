"""Writes eh-embed/src/test/resources/minilm-golden.tsv: reference token ids and vectors.

The Java forward pass (eh-embed) is checked against these, coordinate by coordinate.

    pip install sentence-transformers
    python scripts/golden_embeddings.py ~/.even-hand/models/all-MiniLM-L6-v2 \
        eh-embed/src/test/resources/minilm-golden.tsv
"""
import os
import sys

os.environ.setdefault("USE_TF", "0")
os.environ.setdefault("TRANSFORMERS_NO_TF", "1")

import sentence_transformers  # noqa: E402
from sentence_transformers import SentenceTransformer  # noqa: E402

TEXTS = [
    "The bending moment is maximum where the shear force changes sign.",
    "Maximum BM occurs at the point of zero shear.",
    "The member BC is in compression, so the force is -25 kN.",
    "Using the method of sections, the force in member BC is tensile (25 kN).",
    "Superposition applies because the material stays linear elastic and deflections are small.",
    "I don't know.",
    "Résumé of the café's naïve approach: Δ = PL³/(48EI) ≈ 2.3 mm!!",
    "Line one of the answer.\nLine two continues: σ = My/I, where y is measured from the neutral axis.",
    "x" * 150 + " supercalifragilisticexpialidocious antidisestablishmentarianism",
    "Reynolds number Re=ρVD/μ=1000×2×0.05/0.001=10^5, hence turbulent flow.",
    "日本語 text mixed with English: the soil's effective stress σ' = σ − u.",
    " ".join(["The effective stress principle states that deformation and strength of soil "
              "are governed by effective stress."] * 30),
]


def escape(text):
    return text.replace("\\", "\\\\").replace("\n", "\\n")


def main(model_dir, out_path):
    model = SentenceTransformer(model_dir, device="cpu")
    vectors = model.encode(TEXTS, normalize_embeddings=True, convert_to_numpy=True)
    with open(out_path, "w", encoding="utf-8", newline="\n") as f:
        f.write("# golden embeddings from sentence-transformers %s, all-MiniLM-L6-v2, "
                "normalize_embeddings=True\n" % sentence_transformers.__version__)
        for text, vector in zip(TEXTS, vectors):
            ids = model.tokenizer(text, truncation=True, max_length=model.max_seq_length)["input_ids"]
            f.write(escape(text) + "\t" + ",".join(map(str, ids)) + "\t"
                    + ",".join("%.7g" % x for x in vector) + "\n")
    print("wrote", len(TEXTS), "reference vectors; max_seq_length", model.max_seq_length)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
