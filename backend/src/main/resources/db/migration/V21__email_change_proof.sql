alter table email_verification_challenge add column previous_email varchar(254);
alter table email_verification_challenge add column previous_code_hash varchar(64);
