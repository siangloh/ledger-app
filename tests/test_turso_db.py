from turso_db import TursoConnection


def test_split_statements_ignores_semicolons_inside_single_quotes():
    script = "CREATE TABLE t(a TEXT DEFAULT 'x;y'); INSERT INTO t VALUES ('a;b;c');"
    assert TursoConnection._split_statements(script) == [
        "CREATE TABLE t(a TEXT DEFAULT 'x;y')",
        "INSERT INTO t VALUES ('a;b;c')",
    ]


def test_split_statements_ignores_semicolons_inside_double_quotes():
    script = 'SELECT 1 AS "a;b"; SELECT 2;'
    assert TursoConnection._split_statements(script) == [
        'SELECT 1 AS "a;b"',
        "SELECT 2",
    ]


def test_split_statements_plain_script_unchanged_behavior():
    script = "CREATE TABLE a(id INTEGER); CREATE TABLE b(id INTEGER);"
    assert TursoConnection._split_statements(script) == [
        "CREATE TABLE a(id INTEGER)",
        "CREATE TABLE b(id INTEGER)",
    ]


def test_split_statements_ignores_blank_and_trailing_fragments():
    script = "  CREATE TABLE a(id INTEGER);   ;  \n"
    assert TursoConnection._split_statements(script) == ["CREATE TABLE a(id INTEGER)"]
