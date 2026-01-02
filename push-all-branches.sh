#!/bin/bash
# Script pour pousser toutes les branches vers GitHub
# Exécutez ce script depuis le répertoire agentic-rag-base

echo "========================================="
echo "Push de toutes les branches vers GitHub"
echo "========================================="

# Vérifier la connexion
echo ""
echo "Vérification de la connexion..."
if ! ping -c 1 github.com &> /dev/null; then
    echo "ERREUR: Impossible de se connecter à GitHub.com"
    echo "Vérifiez votre connexion internet et réessayez."
    exit 1
fi

echo "Connexion OK"

# Vérifier que nous sommes dans le bon répertoire
if [ ! -d ".git" ]; then
    echo "ERREUR: Ce script doit être exécuté depuis le répertoire agentic-rag-base"
    exit 1
fi

# Afficher les branches locales
echo ""
echo "Branches locales:"
git branch

# Vérifier l'état
echo ""
echo "Vérification de l'état Git..."
if [ -n "$(git status --short)" ]; then
    echo "ATTENTION: Il y a des modifications non commitées:"
    git status --short
    read -p "Voulez-vous les commiter avant de pousser? (o/n) " response
    if [ "$response" = "o" ] || [ "$response" = "O" ]; then
        git add -A
        read -p "Message de commit: " commitMessage
        if [ -z "$commitMessage" ]; then
            commitMessage="Auto-commit avant push"
        fi
        git commit -m "$commitMessage"
    fi
fi

# Pousser toutes les branches
echo ""
echo "Poussage de toutes les branches..."

# Récupérer toutes les branches locales
branches=$(git branch --format='%(refname:short)')

for branch in $branches; do
    echo ""
    echo "Poussage de la branche: $branch"
    if git push origin "$branch"; then
        echo "✓ Branche $branch poussée avec succès"
    else
        echo "✗ Erreur lors du push de la branche $branch"
    fi
done

# Pousser aussi avec --all pour être sûr
echo ""
echo "Poussage final avec --all..."
git push --all origin

# Pousser les tags aussi
echo ""
echo "Poussage des tags..."
git push --tags origin

echo ""
echo "========================================="
echo "Terminé!"
echo "========================================="
