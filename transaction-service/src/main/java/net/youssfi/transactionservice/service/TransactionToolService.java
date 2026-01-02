package net.youssfi.transactionservice.service;

import net.youssfi.transactionservice.agents.TransactionAiTools;
import net.youssfi.transactionservice.entities.Transaction;
import net.youssfi.transactionservice.entities.TransactionStatus;
import net.youssfi.transactionservice.entities.TransactionType;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class TransactionToolService {
    private TransactionAiTools transactionAiTools;

    public TransactionToolService(TransactionAiTools transactionAiTools) {
        this.transactionAiTools = transactionAiTools;
    }

    /**
     * Analyse la question et appelle les outils appropriés
     * Retourne les données récupérées ou null si aucun outil n'est nécessaire
     */
    public String executeTools(String question) {
        String lowerQuestion = question.toLowerCase();
        
        // Pattern pour détecter "toutes les transactions" ou "all transactions"
        if (lowerQuestion.contains("toutes les transactions") || 
            lowerQuestion.contains("all transactions") ||
            lowerQuestion.contains("liste des transactions") ||
            lowerQuestion.contains("list transactions") ||
            lowerQuestion.contains("afficher toutes les transactions")) {
            List<Transaction> transactions = transactionAiTools.getAllTransactions();
            return formatTransactions(transactions);
        }
        
        // Pattern pour détecter les transactions par account ID
        Pattern accountPattern = Pattern.compile("(?:compte|account|id)\\s*(?:numéro|number|id)?\\s*(?:de|of)?\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
        Matcher accountMatcher = accountPattern.matcher(question);
        if (accountMatcher.find()) {
            try {
                long accountId = Long.parseLong(accountMatcher.group(1));
                List<Transaction> transactions = transactionAiTools.getAllTransactionsByAccountId(accountId);
                return formatTransactions(transactions, accountId);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        
        // Pattern pour détecter le calcul du solde
        Pattern balancePattern = Pattern.compile("(?:solde|balance)\\s+(?:du|de|of)?\\s*(?:compte|account)?\\s*(?:numéro|number|id)?\\s*(?:de|of)?\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
        Matcher balanceMatcher = balancePattern.matcher(question);
        if (balanceMatcher.find()) {
            try {
                long accountId = Long.parseLong(balanceMatcher.group(1));
                double balance = transactionAiTools.calculateAccountBalance(accountId);
                return String.format("Le solde du compte %d est de %.2f", accountId, balance);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        
        // Pattern pour détecter les transactions par statut
        Pattern statusPattern = Pattern.compile("(?:transactions|transaction)\\s+(?:avec|with|en|in)?\\s+(?:le statut|status)?\\s+(PENDING|EXECUTED|CANCELED|CANCELLED)", Pattern.CASE_INSENSITIVE);
        Matcher statusMatcher = statusPattern.matcher(question);
        if (statusMatcher.find()) {
            try {
                String statusStr = statusMatcher.group(1).toUpperCase();
                if (statusStr.equals("CANCELLED")) statusStr = "CANCELED";
                TransactionStatus status = TransactionStatus.valueOf(statusStr);
                List<Transaction> transactions = transactionAiTools.getTransactionsByStatus(status);
                return formatTransactionsByStatus(transactions, status);
            } catch (Exception e) {
                return "Erreur lors de la récupération: " + e.getMessage();
            }
        }
        
        // Pattern pour détecter une transaction par ID
        Pattern transactionIdPattern = Pattern.compile("(?:transaction|la transaction)\\s+(?:numéro|number|id)?\\s*(?:de|of)?\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
        Matcher transactionIdMatcher = transactionIdPattern.matcher(question);
        if (transactionIdMatcher.find()) {
            try {
                Long transactionId = Long.parseLong(transactionIdMatcher.group(1));
                Transaction transaction = transactionAiTools.getTransactionById(transactionId);
                return "Détails de la transaction:\n" + formatTransaction(transaction);
            } catch (Exception e) {
                return "Erreur: " + e.getMessage();
            }
        }
        
        // Pattern pour détecter la mise à jour du statut
        Pattern updatePattern = Pattern.compile("(?:mettre à jour|update|changer|change)\\s+(?:le statut|status)\\s+(?:de|of)?\\s+(?:la transaction|transaction)?\\s*(\\d+)\\s+(?:à|to|en)\\s+(PENDING|EXECUTED|CANCELED|CANCELLED)", Pattern.CASE_INSENSITIVE);
        Matcher updateMatcher = updatePattern.matcher(question);
        if (updateMatcher.find()) {
            try {
                Long transactionId = Long.parseLong(updateMatcher.group(1));
                String statusStr = updateMatcher.group(2).toUpperCase();
                if (statusStr.equals("CANCELLED")) statusStr = "CANCELED";
                TransactionStatus status = TransactionStatus.valueOf(statusStr);
                Transaction transaction = transactionAiTools.updateTransactionStatus(transactionId, status);
                return "Transaction " + transactionId + " mise à jour avec succès. Nouveau statut: " + status + 
                       "\nDétails: " + formatTransaction(transaction);
            } catch (Exception e) {
                return "Erreur lors de la mise à jour: " + e.getMessage();
            }
        }
        
        // Pattern pour détecter la création d'une transaction
        Pattern createPattern = Pattern.compile("(?:créer|create|ajouter|add)\\s+(?:une|a)?\\s*(?:transaction|nouvelle transaction)\\s+(?:pour|for)?\\s*(?:compte|account)?\\s*(\\d+)\\s+(?:montant|amount)?\\s*(\\d+(?:\\.\\d+)?)\\s+(?:type|type)?\\s+(DEBIT|CREDIT)", Pattern.CASE_INSENSITIVE);
        Matcher createMatcher = createPattern.matcher(question);
        if (createMatcher.find()) {
            try {
                long accountId = Long.parseLong(createMatcher.group(1));
                double amount = Double.parseDouble(createMatcher.group(2));
                TransactionType type = TransactionType.valueOf(createMatcher.group(3).toUpperCase());
                Transaction transaction = transactionAiTools.createTransaction(accountId, amount, type, TransactionStatus.PENDING);
                return "Transaction créée avec succès:\n" + formatTransaction(transaction);
            } catch (Exception e) {
                return "Erreur lors de la création: " + e.getMessage();
            }
        }
        
        // Pattern pour détecter la suppression d'une transaction
        Pattern deletePattern = Pattern.compile("(?:supprimer|delete|remove)\\s+(?:la transaction|transaction)?\\s*(?:numéro|number|id)?\\s*(?:de|of)?\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
        Matcher deleteMatcher = deletePattern.matcher(question);
        if (deleteMatcher.find()) {
            try {
                Long transactionId = Long.parseLong(deleteMatcher.group(1));
                String result = transactionAiTools.deleteTransaction(transactionId);
                return result;
            } catch (Exception e) {
                return "Erreur lors de la suppression: " + e.getMessage();
            }
        }
        
        return null;
    }

    private String formatTransactions(List<Transaction> transactions) {
        if (transactions == null || transactions.isEmpty()) {
            return "Aucune transaction trouvée.";
        }
        
        StringBuilder sb = new StringBuilder("Voici toutes les transactions (" + transactions.size() + "):\n\n");
        for (Transaction t : transactions) {
            sb.append(formatTransaction(t)).append("\n");
        }
        return sb.toString();
    }

    private String formatTransactions(List<Transaction> transactions, long accountId) {
        if (transactions == null || transactions.isEmpty()) {
            return "Aucune transaction trouvée pour le compte " + accountId + ".";
        }
        
        StringBuilder sb = new StringBuilder("Voici les transactions du compte " + accountId + " (" + transactions.size() + "):\n\n");
        for (Transaction t : transactions) {
            sb.append(formatTransaction(t)).append("\n");
        }
        return sb.toString();
    }

    private String formatTransaction(Transaction t) {
        return String.format("ID: %d | Compte: %d | Montant: %.2f | Type: %s | Statut: %s | Date: %s",
                t.getId(), t.getAccountId(), t.getAmount(), t.getType(), t.getStatus(), t.getDate());
    }

    private String formatTransactionsByStatus(List<Transaction> transactions, TransactionStatus status) {
        if (transactions == null || transactions.isEmpty()) {
            return "Aucune transaction trouvée avec le statut " + status + ".";
        }
        
        StringBuilder sb = new StringBuilder("Voici les transactions avec le statut " + status + " (" + transactions.size() + "):\n\n");
        for (Transaction t : transactions) {
            sb.append(formatTransaction(t)).append("\n");
        }
        return sb.toString();
    }
}
