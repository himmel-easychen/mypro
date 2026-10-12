import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from audit_rag_acceptance import build_audit


class AuditRagAcceptanceTest(unittest.TestCase):
    def test_prompt_evidence_recall_is_separate_from_search_recall(self):
        rows = {
            "a": {"id": "a", "type": "multi_hop", "relevant_chunk_ids": ["g1", "g2"]},
            "u": {"id": "u", "type": "unanswerable", "relevant_chunk_ids": ["g3"]},
        }
        report = {"samples": [
            {"id": "a", "response": {
                "status": "OK",
                "retrievedChunkIds": ["g1", "g2", "x"],
                "evidence": [{"id": "E1", "chunkId": "g1", "content": "alpha", "parentContent": ""}],
                "retrieval": {"stage_candidates": {"reranked": [
                    {"chunkId": "g1"}, {"chunkId": "g2"}]}}}},
            {"id": "u", "response": {
                "status": "OK", "retrievedChunkIds": ["g3"], "evidence": []}},
        ]}
        chunks = {"g1": {"content": "alpha"}, "g2": {"content": "beta"}, "g3": {"content": "gamma"}}

        audit = build_audit(rows, report, chunks)

        self.assertEqual(audit["summary"]["answerable"], 1)
        self.assertEqual(audit["summary"]["search_k"], 20)
        self.assertEqual(audit["summary"]["search_recall_at_k_mean"], 1.0)
        self.assertEqual(audit["summary"]["search_recall_all_returned_mean"], 1.0)
        self.assertEqual(audit["summary"]["prompt_evidence_recall_mean"], 0.5)
        self.assertEqual(audit["summary"]["prompt_loss_after_search_k_complete"], ["a"])
        self.assertEqual(audit["samples"][0]["gold"][1]["prompt_evidence_ids"], [])

    def test_top_k_search_recall_is_not_confused_with_all_returned_candidates(self):
        rows = {"a": {"id": "a", "type": "basic", "relevant_chunk_ids": ["g"]}}
        report = {"samples": [{"id": "a", "response": {
            "status": "OK",
            "retrievedChunkIds": ["x"] * 20 + ["g"],
            "evidence": [{"id": "E1", "chunkId": "g", "content": "gold"}]}}]}
        audit = build_audit(rows, report, {"g": {"content": "gold"}}, search_k=20)

        self.assertEqual(audit["summary"]["search_recall_at_k_mean"], 0.0)
        self.assertEqual(audit["summary"]["search_recall_all_returned_mean"], 1.0)
        self.assertEqual(audit["summary"]["prompt_loss_after_search_k_complete"], [])
        self.assertEqual(audit["summary"]["search_loss_at_k"], ["a"])


if __name__ == "__main__":
    unittest.main()
