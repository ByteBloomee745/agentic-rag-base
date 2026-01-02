package net.youssfi.transactionservice.agents;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import net.youssfi.transactionservice.entities.Transaction;
import net.youssfi.transactionservice.entities.TransactionStatus;
import net.youssfi.transactionservice.entities.TransactionType;
import net.youssfi.transactionservice.repository.TransactionRepository;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;
import java.util.Optional;

@Component
@Slf4j
public class TransactionAiTools {
    private final TransactionRepository transactionRepository;

    public TransactionAiTools(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    @Tool("Récupère toutes les transactions de la base de données")
    public List<Transaction> getAllTransactions(){
        log.info("Récupération de toutes les transactions depuis la base de données");
        return transactionRepository.findAll();
    }

    @Tool("Récupère toutes les transactions pour un compte spécifique en utilisant l'ID du compte")
    public List<Transaction> getAllTransactionsByAccountId(@P("L'ID du compte") long accountId){
        log.info("Récupération des transactions pour le compte: {}", accountId);
        return transactionRepository.findByAccountId(accountId);
    }

    @Tool("Récupère toutes les transactions avec un statut spécifique (PENDING, EXECUTED, CANCELED)")
    public List<Transaction> getTransactionsByStatus(@P("Le statut de la transaction") TransactionStatus status){
        log.info("Récupération des transactions avec le statut: {}", status);
        return transactionRepository.findByStatus(status);
    }

    @Tool("Récupère une transaction spécifique par son ID")
    public Transaction getTransactionById(@P("L'ID de la transaction") Long transactionId){
        log.info("Récupération de la transaction avec l'ID: {}", transactionId);
        return transactionRepository.findById(transactionId)
                .orElseThrow(() -> new RuntimeException("Transaction non trouvée avec l'ID: " + transactionId));
    }

    @Tool("Met à jour le statut d'une transaction existante")
    public Transaction updateTransactionStatus(
            @P("L'ID de la transaction à mettre à jour") Long transactionId,
            @P("Le nouveau statut (PENDING, EXECUTED, CANCELED)") TransactionStatus transactionStatus){
        log.info("Mise à jour du statut de la transaction {} vers {}", transactionId, transactionStatus);
        Transaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new RuntimeException("Transaction non trouvée avec l'ID: " + transactionId));
        transaction.setStatus(transactionStatus);
        return transactionRepository.save(transaction);
    }

    @Tool("Crée une nouvelle transaction dans la base de données")
    public Transaction createTransaction(
            @P("L'ID du compte") long accountId,
            @P("Le montant de la transaction") double amount,
            @P("Le type de transaction (DEBIT ou CREDIT)") TransactionType type,
            @P("Le statut initial (PENDING, EXECUTED, CANCELED)") TransactionStatus status){
        log.info("Création d'une nouvelle transaction: compte={}, montant={}, type={}, statut={}", 
                accountId, amount, type, status);
        Transaction transaction = Transaction.builder()
                .accountId(accountId)
                .amount(amount)
                .type(type)
                .status(status)
                .date(new Date())
                .build();
        return transactionRepository.save(transaction);
    }

    @Tool("Supprime une transaction de la base de données")
    public String deleteTransaction(@P("L'ID de la transaction à supprimer") Long transactionId){
        log.info("Suppression de la transaction avec l'ID: {}", transactionId);
        if (transactionRepository.existsById(transactionId)) {
            transactionRepository.deleteById(transactionId);
            return "Transaction " + transactionId + " supprimée avec succès";
        } else {
            return "Transaction non trouvée avec l'ID: " + transactionId;
        }
    }

    @Tool("Calcule le solde total pour un compte spécifique (somme des crédits moins somme des débits)")
    public double calculateAccountBalance(@P("L'ID du compte") long accountId){
        log.info("Calcul du solde pour le compte: {}", accountId);
        List<Transaction> transactions = transactionRepository.findByAccountId(accountId);
        double balance = 0.0;
        for (Transaction t : transactions) {
            if (t.getType() == TransactionType.CREDIT) {
                balance += t.getAmount();
            } else if (t.getType() == TransactionType.DEBIT) {
                balance -= t.getAmount();
            }
        }
        return balance;
    }
}