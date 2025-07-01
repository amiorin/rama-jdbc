set shell := ["bash", "-euo", "pipefail", "-c"]

postgres_home := `cat .envrc | grep "export PGHOME" | cut -d'=' -f2-`
pg_port :=`shuf -i 49152-65535 -n 1`

setup-env-vars:
    echo "### Added by just: start ###" >> .envrc.private
    echo "export PGPORT={{pg_port}}" >> .envrc.private
    echo "### Added by just: end ###" >> .envrc.private

init-postgres:
    direnv exec . mkdir -p $PGDATA
    direnv exec . initdb

run-postgres-in-background:
    direnv exec . nohup overmind start -l postgres &

setup-postgres-db:
    direnv exec . createuser -h {{postgres_home}} rama_jdbc_user
    direnv exec . createdb -h {{postgres_home}} rama_jdbc_db
    direnv exec . psql -h {{postgres_home}} -U rama_jdbc_user -d rama_jdbc_db -f init-db.sql

psql:
    direnv exec . psql -h {{postgres_home}} -U rama_jdbc_user -d rama_jdbc_db

direnv-allow:
    direnv allow

sleep-5:
    sleep 5

setup-dev: direnv-allow setup-env-vars init-postgres run-postgres-in-background sleep-5 setup-postgres-db stop-processes stop-overmind sleep-5

run-processes:
    direnv exec . nohup overmind start &

run-jdbc-poller:
    mvn exec:java -Dexec.mainClass="com.of.rama.jdbc.PostgresToRamaPoller"

remove-postgres:
    rm -rf postgres

remove-env-vars:
    # find start and end line positions and delete everything in between
    sed -i '/just: start/,/just: end/d' .envrc.private

stop-processes:
    if [ -S .overmind.sock ]; then direnv exec . overmind stop; else echo "Overmind is not running. Skipping."; fi

stop-overmind:
    if [ -S .overmind.sock ]; then direnv exec . overmind kill; else echo "Overmind is not running. Skipping."; fi

stop-dev: stop-processes stop-overmind

remove-dev: stop-processes stop-overmind remove-postgres remove-env-vars
