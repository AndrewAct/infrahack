-- Demo user matching bootstrap/SampleData: ada@example.com / correct-horse-battery.
-- Hash format is salt:sha256hex(salt + password), same as util/PasswordHasher.

INSERT INTO password_reset_users (email, password_hash)
VALUES ('ada@example.com',
        '5eedc0de5a17f00d:d5b9099979c7ed1ba8c5ba73936979491ee0369ae84bcb04824884f182c7542e')
ON CONFLICT (email) DO UPDATE SET password_hash = EXCLUDED.password_hash, updated_at = now();
