#!/bin/sh
# ==============================================================================================
# ONE-TIME BOOTSTRAP · runs on the database's very first start, never again.
#
# The postgres image executes everything in /docker-entrypoint-initdb.d once, when the data
# directory is empty. Re-running it means deleting the volume, which means deleting the database.
#
# Why a second role exists at all:
#
# Flyway migrates as the owner (postgres) because it creates tables, extensions and partitions.
# The application connects as this weaker role, which has no DDL rights and, critically, no
# UPDATE or DELETE on audit_log. That is what makes "the audit log is append-only" a database
# guarantee rather than a promise about our own code. A superuser connection would silently
# ignore every one of those restrictions.
#
# This is a shell script rather than the .sql it used to be so that the password comes from the
# environment. It was previously written into the file as a literal, which meant the production
# database's application password was published in the repository and identical in every
# deployment that never noticed.
# ==============================================================================================
set -e

: "${DB_USER:?DB_USER must be set — the application's database role}"
: "${DB_PASSWORD:?DB_PASSWORD must be set — see .env.example}"

# Refuse the value the repository used to ship — but only where it matters. It is public, so
# anyone can read it, and a production deployment that kept it would look exactly like one that
# had been configured properly.
#
# Gated on STRICT_ROLE_PASSWORD, which only docker-compose.yml sets. On a development machine
# `mlmsittu_app` is the documented default and refusing it would just break the local stack for
# no gain; the database there is not reachable from anywhere and holds nothing real.
if [ "${STRICT_ROLE_PASSWORD:-false}" = "true" ] && [ "$DB_PASSWORD" = "mlmsittu_app" ]; then
    echo "01-app-role.sh: DB_PASSWORD is still the published example value." >&2
    echo "                Generate one with: openssl rand -base64 24" >&2
    exit 1
fi

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
     -v app_user="$DB_USER" -v app_password="$DB_PASSWORD"      -v db_name="$POSTGRES_DB" <<'EOSQL'

-- format()/\gexec rather than a DO block: psql does not substitute :variables inside
-- dollar-quoted strings, so the password would have been sent to the server literally as
-- ":'app_password'" and the role would exist with a password nobody could guess or use.
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'app_user', :'app_password')
 WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'app_user')
\gexec

GRANT CONNECT ON DATABASE :"db_name" TO :"app_user";
GRANT USAGE ON SCHEMA public TO :"app_user";

-- Objects that already exist.
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO :"app_user";
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO :"app_user";

-- Objects Flyway creates from now on. Without this every new migration would need explicit
-- grants, and one forgotten line would break the application at runtime rather than at
-- migration time.
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO :"app_user";
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO :"app_user";

-- The application must never issue DDL. Hibernate runs in validate mode; Flyway owns the schema.
REVOKE CREATE ON SCHEMA public FROM :"app_user";

EOSQL

echo "01-app-role.sh: role '$DB_USER' is ready."
