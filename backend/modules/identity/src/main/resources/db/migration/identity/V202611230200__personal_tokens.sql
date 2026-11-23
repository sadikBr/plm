-- People's own access tokens (roadmap 8.3).
alter table api_token drop constraint api_token_kind_check;
alter table api_token add constraint api_token_kind_check
    check (kind in ('API_TOKEN', 'CLIENT_SECRET', 'ACCESS', 'DELEGATED', 'PERSONAL'));
