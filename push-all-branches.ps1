# Script pour pousser toutes les branches vers GitHub
# Exécutez ce script depuis le répertoire agentic-rag-base

Write-Host "=========================================" -ForegroundColor Cyan
Write-Host "Push de toutes les branches vers GitHub" -ForegroundColor Cyan
Write-Host "=========================================" -ForegroundColor Cyan

# Vérifier la connexion
Write-Host "`nVérification de la connexion..." -ForegroundColor Yellow
$testConnection = Test-Connection -ComputerName github.com -Count 1 -Quiet
if (-not $testConnection) {
    Write-Host "ERREUR: Impossible de se connecter à GitHub.com" -ForegroundColor Red
    Write-Host "Vérifiez votre connexion internet et réessayez." -ForegroundColor Red
    exit 1
}

Write-Host "Connexion OK" -ForegroundColor Green

# Vérifier que nous sommes dans le bon répertoire
if (-not (Test-Path ".git")) {
    Write-Host "ERREUR: Ce script doit être exécuté depuis le répertoire agentic-rag-base" -ForegroundColor Red
    exit 1
}

# Afficher les branches locales
Write-Host "`nBranches locales:" -ForegroundColor Yellow
git branch

# Vérifier l'état
Write-Host "`nVérification de l'état Git..." -ForegroundColor Yellow
$status = git status --short
if ($status) {
    Write-Host "ATTENTION: Il y a des modifications non commitées:" -ForegroundColor Yellow
    git status --short
    $response = Read-Host "Voulez-vous les commiter avant de pousser? (o/n)"
    if ($response -eq "o" -or $response -eq "O") {
        git add -A
        $commitMessage = Read-Host "Message de commit"
        if (-not $commitMessage) {
            $commitMessage = "Auto-commit avant push"
        }
        git commit -m $commitMessage
    }
}

# Pousser toutes les branches
Write-Host "`nPoussage de toutes les branches..." -ForegroundColor Yellow

# Récupérer toutes les branches locales
$branches = git branch --format='%(refname:short)'

foreach ($branch in $branches) {
    Write-Host "`nPoussage de la branche: $branch" -ForegroundColor Cyan
    git push origin $branch
    if ($LASTEXITCODE -eq 0) {
        Write-Host "✓ Branche $branch poussée avec succès" -ForegroundColor Green
    } else {
        Write-Host "✗ Erreur lors du push de la branche $branch" -ForegroundColor Red
    }
}

# Pousser aussi avec --all pour être sûr
Write-Host "`nPoussage final avec --all..." -ForegroundColor Yellow
git push --all origin

# Pousser les tags aussi
Write-Host "`nPoussage des tags..." -ForegroundColor Yellow
git push --tags origin

Write-Host "`n=========================================" -ForegroundColor Cyan
Write-Host "Terminé!" -ForegroundColor Green
Write-Host "=========================================" -ForegroundColor Cyan
