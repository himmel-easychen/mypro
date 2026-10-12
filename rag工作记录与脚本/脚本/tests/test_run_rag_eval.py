import unittest
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from run_rag_eval import classify_multihop_failures, evaluate_records, evaluate_generation


class RunRagEvalTest(unittest.TestCase):
    def test_generation_distinguishes_model_failure_from_refusal(self):
        rows = [{"id": "u", "type": "unanswerable", "query": "q", "expected": {"answer": "未提及"}}]
        report = evaluate_generation(rows, lambda row: {"status": "ERROR", "error": "offline"}, [5, 10])
        self.assertIsNone(report["refusal"]["accuracy"])
        self.assertEqual(report["generation"]["measured"], 0)
        self.assertEqual(report["failure_table"][0]["bucket"], "infrastructure_error")
        report = evaluate_generation(rows, lambda row: {"status": "OK", "refusal": True, "answer": "材料未提及"}, [5, 10])
        self.assertEqual(report["refusal"]["accuracy"], 1)
        self.assertFalse(report["closed_loop_passed"])

    def test_citation_mapping_is_not_entailment_and_trace_is_kept(self):
        rows = [{"id": "a", "type": "multi_hop", "query": "q", "relevant_chunk_ids": ["c1"],
                 "expected": {"answer": "20万元"}}]
        response = {"status": "OK", "answer": "20万元[E1]", "prompt": "actual", "refusal": False,
                    "evidence": [{"id": "E1", "chunkId": "c1"}], "retrievedChunkIds": ["c1"]}
        report = evaluate_generation(rows, lambda row: response, [5, 10])
        self.assertEqual(report["citations"]["id_mapping_rate"], 1)
        self.assertIsNone(report["citations"]["support_accuracy"])
        self.assertEqual(report["samples"][0]["response"]["prompt"], "actual")
        self.assertTrue(report["samples"][0]["checks"]["expected_numbers_present"])

    def test_review_keeps_context_and_prompt_failures_distinct(self):
        rows = [{"id": "a", "type": "semantic", "query": "q", "relevant_chunk_ids": ["c1"]}]
        response = {"status": "OK", "answer": "事实[E1]", "refusal": False,
                    "evidence": [{"id": "E1"}], "retrievedChunkIds": ["c1"]}
        review = {"a": {"status": "reviewed", "required_facts_covered": False,
                         "numeric_unit_correct": None, "citation_supported": False,
                         "failure_buckets": ["context_loss", "prompt_loss", "llm_answer_error"]}}
        report = evaluate_generation(rows, lambda row: response, [5, 10], review)
        self.assertFalse(report["closed_loop_passed"])
        self.assertEqual(report["generation"]["required_facts_accuracy"], 0)
        self.assertEqual({f["bucket"] for f in report["failure_table"]},
                         {"context_loss", "prompt_loss", "llm_answer_error", "citation_error"})
    def test_negative_review_without_bucket_cannot_pass(self):
        rows = [{"id": "a", "type": "semantic", "query": "q", "relevant_chunk_ids": ["c1"]}]
        response = {"status": "OK", "answer": "事实[E1]", "refusal": False,
                    "evidence": [{"id": "E1"}], "retrievedChunkIds": ["c1"]}
        review = {"a": {"status": "reviewed", "required_facts_covered": False,
                         "numeric_unit_correct": None, "citation_supported": True}}
        report = evaluate_generation(rows, lambda row: response, [5, 10], review)
        self.assertFalse(report["closed_loop_passed"])
        self.assertIn({"id": "a", "bucket": "llm_answer_error"}, report["failure_table"])

    def test_review_can_waive_known_auto_failure_bucket(self):
        rows = [{"id": "a", "type": "unanswerable", "query": "q", "expected": {"answer": "未提及"}}]
        response = {"status": "OK", "answer": "有依据的答案[E1]", "refusal": False,
                    "evidence": [{"id": "E1"}], "retrievedChunkIds": []}
        review = {"a": {"status": "reviewed", "required_facts_covered": True,
                         "numeric_unit_correct": None, "citation_supported": True,
                         "waived_failure_buckets": ["refusal_error"]}}
        report = evaluate_generation(rows, lambda row: response, [5, 10], review)
        self.assertNotIn({"id": "a", "bucket": "refusal_error"}, report["failure_table"])

    def test_retrieval_metrics_and_multihop(self):
        records = [
            {"id": "a", "type": "semantic", "relevant_chunk_ids": ["c1"],
             "expected": {"answer": "x"}},
            {"id": "m", "type": "multi_hop", "relevant_chunk_ids": ["c2", "c3"],
             "expected": {"answer": "x"}},
            {"id": "u", "type": "unanswerable", "relevant_chunk_ids": ["unknown"],
             "expected": {"answer": "未提及"}},
        ]
        responses = {
            "a": ["c1", "other"],
            "m": ["other", "c2", "c3"],
            "u": ["other"],
        }
        result = evaluate_records(records, lambda row: responses[row["id"]], [1, 3, 5])
        self.assertEqual(result["counts"]["answerable"], 2)
        self.assertEqual(result["retrieval"]["hit@1"], 0.5)
        self.assertEqual(result["retrieval"]["recall@3"], 1.0)
        self.assertEqual(result["retrieval"]["mrr@5"], 0.75)
        self.assertEqual(result["multi_hop"]["complete@5"], 1.0)
        self.assertEqual(result["refusal"]["eligible"], 1)

    def test_classifies_each_multihop_failure_stage_and_records_gold_ranks(self):
        records = [
            {"id": category, "type": "multi_hop", "relevant_chunk_ids": ["g1", "g2"]}
            for category in ("provider_miss", "partial_provider_recall", "fusion_drop",
                             "rerank_drop", "context_drop", "complete")
        ]
        common = {
            "vector": ["g1", "g2"], "bm25": [], "structured": [],
            "fused": ["g1", "g2"], "reranked": ["g1", "g2"],
            "final_context": ["g1", "g2"],
        }
        layered = {row["id"]: {key: list(value) for key, value in common.items()} for row in records}
        layered["provider_miss"].update({key: [] for key in common})
        layered["partial_provider_recall"].update({key: ["g1"] for key in common})
        layered["fusion_drop"]["fused"] = ["g1"]
        layered["fusion_drop"]["reranked"] = ["g1"]
        layered["fusion_drop"]["final_context"] = ["g1"]
        layered["rerank_drop"]["reranked"] = ["g1"]
        layered["rerank_drop"]["final_context"] = ["g1"]
        layered["context_drop"]["final_context"] = ["g1"]

        result = classify_multihop_failures(records, layered)

        self.assertEqual(result["category_counts"], {
            "provider_miss": 1, "partial_provider_recall": 1, "fusion_drop": 1,
            "rerank_drop": 1, "context_drop": 1, "complete": 1,
        })
        self.assertEqual(result["funnel"]["provider_complete"], 4)
        self.assertEqual(result["funnel"]["final_context_complete"], 1)
        complete = next(case for case in result["cases"] if case["id"] == "complete")
        self.assertEqual(complete["gold_chunks"][0]["ranks"]["vector"], 1)
        self.assertEqual(complete["gold_chunks"][1]["ranks"]["final_context"], 2)


if __name__ == "__main__":
    unittest.main()
