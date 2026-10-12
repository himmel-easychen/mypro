import json
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from run_rag_eval import http_answer
from run_rag_distractor_eval import inject, call_model, ModelOutputError, normalize_bare_citations


class ScopeTests(unittest.TestCase):
    def test_invalid_model_json_preserves_output_for_diagnosis(self):
        payload = {"id": "response-test", "status": "completed", "output": [
            {"type": "function_call", "name": "submit_answer", "arguments": "{broken json"}]}
        with patch("urllib.request.urlopen") as call:
            call.return_value.__enter__.return_value.read.return_value = json.dumps(payload).encode()
            with self.assertRaises(ModelOutputError) as caught:
                call_model("question", "test-key", "https://example.test/v1/responses", "gpt-test")
        self.assertEqual(caught.exception.raw_response, "{broken json")
        self.assertEqual(caught.exception.response_id, "response-test")

    def test_duplicate_model_json_key_is_rejected(self):
        payload = {"id": "response-test", "status": "completed", "output": [
            {"type": "function_call", "name": "submit_answer",
             "arguments": '{"supported":true,"answer":"ok","answer":"duplicate"}'}]}
        with patch("urllib.request.urlopen") as call:
            call.return_value.__enter__.return_value.read.return_value = json.dumps(payload).encode()
            with self.assertRaises(ModelOutputError) as caught:
                call_model("question", "test-key", "https://example.test/v1/responses", "gpt-test")
        self.assertEqual(caught.exception.raw_response, payload["output"][0]["arguments"])

    def test_distractor_request_forces_answer_function(self):
        payload = {"id": "chat-test", "choices": [{"message": {"tool_calls": [
            {"function": {"name": "submit_answer",
                          "arguments": '{"supported":true,"answerability":"FULL","answer":"ok[E1]"}'}}]}}]}
        with patch("urllib.request.urlopen") as call:
            call.return_value.__enter__.return_value.read.return_value = json.dumps(payload).encode()
            self.assertEqual(call_model("question", "test-key", "https://example.test", "gpt-test")["answer"], "ok[E1]")
            body = json.loads(call.call_args.args[0].data)
        self.assertEqual(body["tool_choice"], {"type": "function", "function": {"name": "submit_answer"}})
        self.assertEqual(body["tools"][0]["function"]["parameters"]["additionalProperties"], False)
        self.assertEqual(body["messages"][0]["content"], "question")

    def test_full_corpus_does_not_leak_document_or_gold(self):
        row = {"query": "question", "source_document_id": "doc", "expected": {"answer": "secret"},
               "relevant_chunk_ids": ["gold"]}
        with patch("urllib.request.urlopen") as call:
            call.return_value.__enter__.return_value.read.return_value = b'{"status":"OK"}'
            http_answer("http://localhost", row, full_corpus=True)
            body = json.loads(call.call_args.args[0].data)
            self.assertEqual(body, {"query": "question", "disableQueryTransform": False})

    def test_injection_preserves_original_evidence_and_unique_ids(self):
        original = {"question": "q", "history": [], "evidence": [
            {"id": "E1", "content": "fact", "parentContent": "full parent"}]}
        prompt = "instructions\nINPUT_JSON:\n" + json.dumps(original)
        extra = [{"content": "distractor"}]
        for position in ("prepend", "append"):
            rendered, evidence, ids = inject(prompt, extra, position)
            self.assertIn(original["evidence"][0], evidence)
            self.assertEqual(ids, ["E2"])
            self.assertNotIn("id", extra[0])
            self.assertEqual(json.loads(rendered.split("\nINPUT_JSON:\n")[1])["question"], "q")

    def test_bare_citation_ids_are_normalized_when_evidence_exists(self):
        answer = "依据E1、E2，但不要改E9。"
        evidence = [{"id": "E1"}, {"id": "E2"}]
        self.assertEqual(normalize_bare_citations(answer, evidence), "依据[E1]、[E2]，但不要改E9。")


if __name__ == "__main__":
    unittest.main()
