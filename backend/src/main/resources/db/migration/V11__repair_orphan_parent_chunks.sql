-- Historical ingests could create child chunks before their parent row.
-- Reattach those children to the first parent of the same document so
-- retrieval provenance remains complete after a restart.
with first_parent as (
    select distinct on (document_id) document_id, id as parent_id
    from document_chunk
    where chunk_role = 'parent'
    order by document_id, id
)
update document_chunk child
set parent_chunk_id = first_parent.parent_id,
    updated_at = now()
from first_parent
where child.document_id = first_parent.document_id
  and child.chunk_role = 'child'
  and child.parent_chunk_id is null;

create index if not exists idx_document_chunk_parent_lookup
    on document_chunk(parent_chunk_id, chunk_role);
