-- Enable pgvector extension
CREATE EXTENSION IF NOT EXISTS vector;

-- Create RAG schema
CREATE SCHEMA IF NOT EXISTS rag;

-- Grant privileges to teaa user
GRANT ALL ON SCHEMA rag TO teaa;
GRANT ALL ON SCHEMA public TO teaa;
