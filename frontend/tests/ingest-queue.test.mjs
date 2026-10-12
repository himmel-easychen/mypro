import test from 'node:test';
import assert from 'node:assert/strict';
import {mergeFiles, summarizeBatch} from '../src/ingestQueue.mjs';

test('mergeFiles keeps valid files from multiple selections and removes duplicates', () => {
  const first = {name: 'a.pdf', size: 10, lastModified: 1};
  const duplicate = {name: 'a.pdf', size: 10, lastModified: 1};
  const second = {name: 'b.docx', size: 20, lastModified: 2};

  assert.deepEqual(mergeFiles([first], [duplicate, second]), [first, second]);
});

test('summarizeBatch reports completed, failed and aggregate page metrics', () => {
  const summary = summarizeBatch([
    {name: 'a.pdf', status: 'succeeded', total_pages: 4, chunk_count: 8, page_pass_rate: 1},
    {name: 'b.pdf', status: 'failed', error: 'OCR failed'},
  ]);

  assert.deepEqual(summary, {
    total: 2,
    succeeded: 1,
    failed: 1,
    total_pages: 4,
    chunk_count: 8,
    page_pass_rate: 1,
  });
});
