-- PDF previews (roadmap 3.2): pages are rendered once and kept next to the file.
alter table vault_blob add column pages integer;
