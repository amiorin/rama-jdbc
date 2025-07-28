SET autoinstall_known_extensions = true;
SET autoload_known_extensions = true;

SET ui_local_port = {{duckdb-port}};
INSTALL ui;
LOAD ui;
CALL start_ui_server();

INSTALL postgres;
LOAD postgres;
ATTACH 'dbname={{postgres-dbname}} user={{postgres-user}} host=127.0.0.1 port={{postgres-port}}' AS rama (TYPE postgres, READ_ONLY);
