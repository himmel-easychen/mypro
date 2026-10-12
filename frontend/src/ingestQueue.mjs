function fileKey(file) {
  return `${file.name}:${file.size}:${file.lastModified ?? 0}`;
}

export function mergeFiles(existing, incoming) {
  const merged = [...existing];
  const seen = new Set(existing.map(fileKey));
  for (const file of incoming) {
    const key = fileKey(file);
    if (!seen.has(key)) {
      merged.push(file);
      seen.add(key);
    }
  }
  return merged;
}

export function summarizeBatch(items) {
  const succeeded = items.filter((item) => item.status === 'succeeded');
  const totalPages = succeeded.reduce((sum, item) => sum + Number(item.total_pages || 0), 0);
  const chunkCount = succeeded.reduce((sum, item) => sum + Number(item.chunk_count || 0), 0);
  const evaluatedPages = succeeded.filter((item) => Number.isFinite(Number(item.page_pass_rate)));
  const passRate = evaluatedPages.length
    ? evaluatedPages.reduce((sum, item) => sum + Number(item.page_pass_rate || 0), 0) / evaluatedPages.length
    : 0;
  return {
    total: items.length,
    succeeded: succeeded.length,
    failed: items.length - succeeded.length,
    total_pages: totalPages,
    chunk_count: chunkCount,
    page_pass_rate: passRate,
  };
}
