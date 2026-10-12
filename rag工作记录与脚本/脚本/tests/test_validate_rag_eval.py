import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from validate_rag_eval import validate_records


def write_jsonl(path: Path, rows):
    path.write_text("\n".join(json.dumps(row, ensure_ascii=False) for row in rows) + "\n", encoding="utf-8")


class ValidateRagEvalTest(unittest.TestCase):
    def test_validate_records_accepts_complete_eval_set(self):
        with self.subTest("complete enhanced sample distribution"):
            rows = []
            for index, sample_type in enumerate(
                ["multi_hop", "compare_mutation", "context_confusion", "unanswerable"] * 10,
                start=1,
            ):
                rows.append(
                    {
                        "id": f"case-{index}",
                        "type": sample_type,
                        "expected": {"answer": "未提及" if sample_type == "unanswerable" else "answer"},
                        "relevant_chunk_ids": [f"chunk-{index}"],
                    }
                )
            for index in range(41, 51):
                rows.append(
                    {
                        "id": f"base-{index}",
                        "type": "semantic",
                        "expected": {"answer": "answer"},
                        "relevant_chunk_ids": [f"chunk-{index}"],
                    }
                )
            chunks = [{"relevant_chunk_ids": [f"chunk-{index}"]} for index in range(1, 51)]

            tmp_path = Path(self._testMethodName)
            tmp_path.mkdir(exist_ok=True)
            try:
                eval_path = tmp_path / "eval.jsonl"
                chunks_path = tmp_path / "chunks.jsonl"
                write_jsonl(eval_path, rows)
                write_jsonl(chunks_path, chunks)

                result = validate_records(eval_path, chunks_path)

                self.assertEqual([], result)
                # Revised labels require an explicit distribution; the frozen default stays strict.
                for row in [r for r in rows if r["type"] == "unanswerable"][:2]:
                    row["type"] = "basic"
                    row["expected"]["answer"] = "answer"
                write_jsonl(eval_path, rows)
                self.assertTrue(any("expected 10 unanswerable" in e for e in validate_records(eval_path, chunks_path)))
                self.assertEqual([], validate_records(eval_path, chunks_path, expected_unanswerable=8))
            finally:
                for child in tmp_path.glob("*"):
                    child.unlink()
                tmp_path.rmdir()

    def test_validate_records_reports_wrong_refusal_answer_and_missing_chunk(self):
        rows = [
            {
                "id": "unanswerable-001",
                "type": "unanswerable",
                "expected": {"answer": "错误"},
                "relevant_chunk_ids": ["missing"],
            }
        ]
        tmp_path = Path(self._testMethodName)
        tmp_path.mkdir(exist_ok=True)
        try:
            eval_path = tmp_path / "eval.jsonl"
            chunks_path = tmp_path / "chunks.jsonl"
            write_jsonl(eval_path, rows)
            write_jsonl(chunks_path, [])

            errors = validate_records(eval_path, chunks_path)

            self.assertTrue(any("unanswerable expected.answer must be 未提及" in error for error in errors))
            self.assertTrue(any("missing relevant_chunk_id: missing" in error for error in errors))
        finally:
            for child in tmp_path.glob("*"):
                child.unlink()
            tmp_path.rmdir()


if __name__ == "__main__":
    unittest.main()
