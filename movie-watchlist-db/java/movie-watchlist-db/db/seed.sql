-- Sample data. The UUIDs here mirror io.infrahack.moviewatchlistdb.bootstrap.SampleData exactly, so the
-- same curl commands work whether the app runs in-memory or against this database.
-- Run after schema.sql. Idempotent.

INSERT INTO movies (id, title, release_year, director, actors, genres) VALUES
    ('00000000-0000-0000-0000-000000000001', 'The Matrix',      1999, 'Lana Wachowski',
     ARRAY['Keanu Reeves', 'Carrie-Anne Moss', 'Laurence Fishburne'],
     ARRAY['sci-fi', 'action']),
    ('00000000-0000-0000-0000-000000000002', 'Inception',       2010, 'Christopher Nolan',
     ARRAY['Leonardo DiCaprio', 'Joseph Gordon-Levitt', 'Elliot Page'],
     ARRAY['sci-fi', 'thriller']),
    ('00000000-0000-0000-0000-000000000003', 'Interstellar',    2014, 'Christopher Nolan',
     ARRAY['Matthew McConaughey', 'Anne Hathaway', 'Jessica Chastain'],
     ARRAY['sci-fi', 'drama']),
    ('00000000-0000-0000-0000-000000000004', 'The Dark Knight', 2008, 'Christopher Nolan',
     ARRAY['Christian Bale', 'Heath Ledger', 'Aaron Eckhart'],
     ARRAY['action', 'crime', 'drama']),
    ('00000000-0000-0000-0000-000000000005', 'Parasite',        2019, 'Bong Joon Ho',
     ARRAY['Song Kang-ho', 'Lee Sun-kyun', 'Cho Yeo-jeong'],
     ARRAY['thriller', 'drama', 'comedy']),
    ('00000000-0000-0000-0000-000000000006', 'Spirited Away',   2001, 'Hayao Miyazaki',
     ARRAY['Rumi Hiiragi', 'Miyu Irino', 'Mari Natsuki'],
     ARRAY['animation', 'fantasy', 'adventure']),
    ('00000000-0000-0000-0000-000000000007', 'Whiplash',        2014, 'Damien Chazelle',
     ARRAY['Miles Teller', 'J.K. Simmons', 'Melissa Benoist'],
     ARRAY['drama', 'music']),
    ('00000000-0000-0000-0000-000000000008', 'Dune',            2021, 'Denis Villeneuve',
     ARRAY['Timothee Chalamet', 'Rebecca Ferguson', 'Oscar Isaac'],
     ARRAY['sci-fi', 'adventure'])
ON CONFLICT (id) DO UPDATE
SET title = EXCLUDED.title,
    release_year = EXCLUDED.release_year,
    director = EXCLUDED.director,
    actors = EXCLUDED.actors,
    genres = EXCLUDED.genres;

INSERT INTO watchlists (id, owner_id, name) VALUES
    ('11111111-1111-1111-1111-111111111111',
     '22222222-2222-2222-2222-222222222222',
     'My Watch List')
ON CONFLICT (id) DO NOTHING;
