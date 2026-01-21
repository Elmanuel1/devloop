-- YugabyteDB initialization script
-- Create the tosspaper database
CREATE DATABASE tosspaper;

-- Connect to the tosspaper database
\c tosspaper;

-- Enable required extensions in public schema
CREATE EXTENSION IF NOT EXISTS "uuid-ossp" SCHEMA public;
CREATE EXTENSION IF NOT EXISTS "pgcrypto" SCHEMA public;
CREATE EXTENSION IF NOT EXISTS "vector" SCHEMA public;
