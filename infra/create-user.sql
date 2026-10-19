-- Run ONCE as the DBA against your DEVELOPMENT PDB, not the root CDB.

-- Replace this password before running with F5 in SQL Developer.

CREATE USER fxlock IDENTIFIED BY "thoke@123" QUOTA 200M ON USERS;

GRANT CREATE SESSION, CREATE TABLE, CREATE SEQUENCE TO fxlock;

-- Start Event Service next: Flyway applies V1__fxlock.sql automatically.

