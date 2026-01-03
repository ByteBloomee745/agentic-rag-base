# Dossier de Documents pour RAG

Ce dossier contient les documents qui seront chargés dans le vector store pour le RAG (Retrieval-Augmented Generation).

## Formats supportés

- **PDF** (.pdf) : Les PDFs sont traités avec extraction du texte et des images. Les images sont analysées avec le modèle LLM pour générer des descriptions.
- **Texte** (.txt) : Les fichiers texte sont chargés directement.
- **Images** (.png, .jpg, .jpeg, .gif) : Les images sont analysées avec le modèle LLM pour générer des descriptions.

## Utilisation

1. Placez vos documents dans ce dossier (`src/main/resources/docs/`)
2. Au démarrage de l'application, les documents seront automatiquement chargés dans le vector store PostgreSQL
3. L'agent pourra ensuite utiliser ces documents pour répondre aux questions

## Configuration

La configuration se trouve dans `application.properties` :
- `rag.postgres.*` : Configuration PostgreSQL
- `rag.document.*` : Configuration du découpage des documents
- `rag.retriever.*` : Configuration de la récupération
