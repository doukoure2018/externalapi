package crg.api.external.service.impl;

import crg.api.external.dto.AccessDto;
import crg.api.external.dto.PackageDto;
import crg.api.external.dto.TokenResponse;
import crg.api.external.dto.reabo.PackageDetailsResponse;
import crg.api.external.dto.reabo.ReabonnementRequest;
import crg.api.external.dto.reabo.TransactionDto;
import crg.api.external.enumeration.ValidationStatus;
import crg.api.external.exception.SubscriberPhoneNotFoundException;
import crg.api.external.repository.AccessRepository;
import crg.api.external.repository.ReabonnementRepository;
import crg.api.external.service.OrangeSmsService;
import crg.api.external.service.ReabonnementService;
import crg.api.external.service.SlackService;
import crg.api.external.util.ValidationResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.JavascriptExecutor;
import io.github.bonigarcia.wdm.WebDriverManager;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.openqa.selenium.*;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.support.ui.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URL;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class ReabonnementServiceImpl implements ReabonnementService {

    private final OrangeSmsService orangeSmsService;
    private final JdbcTemplate jdbcTemplate;

    private final SlackService slackService;

    private final AccessRepository accessRepository;

    private final ReabonnementRepository reabonnementRepository;

    private final Map<String, LocalDateTime> blocked2FAAccounts = new ConcurrentHashMap<>();
    private static final int BLOCK_DURATION_MINUTES = 30; // Durée de blocage temporaire

    @Value("${selenium.remote.url:}")
    private String seleniumRemoteUrl;
    @Value("${chrome.remote.enabled:false}")
    private boolean chromeRemoteEnabled;

    @Value("${sms.sender.name}")
    private String senderName;

    @Value("${slack.credential}")
    private String slackCredential;

    @Value("${payment.test.mode:false}")
    private boolean paymentTestMode;

    // Pool de drivers pour réutilisation - Augmenté pour VPS Elite
    private final BlockingQueue<WebDriver> driverPool = new LinkedBlockingQueue<>(10);
    private final ScheduledExecutorService poolMaintainer = Executors.newScheduledThreadPool(1);
    private final ExecutorService smsExecutor = Executors.newSingleThreadExecutor();

    // Pré-compilation des sélecteurs
    private static final By LOGIN_INPUT = By.cssSelector("input[data-cy='login_input']");
    private static final By PASSWORD_INPUT = By.cssSelector("input[data-cy='password_input']");
    private static final By LOGIN_BUTTON = By.cssSelector("button[data-cy='button_input']");
    private static final By SUBSCRIBER_INPUT = By.cssSelector("input[data-cy='Subscriber']");
    private static final By SEARCH_BUTTON = By.cssSelector("button[data-cy='search-btn']");
    private static final By SELECT_SUBSCRIBER = By.cssSelector("button[data-cy='select-subscriber']");
    private static final By SUBSCRIBER_VALID = By.cssSelector("button[data-cy='subscriber-valid']");
    private static final By RENEWAL_QUICK = By.cssSelector("button[data-cy='renewal-quick']");
    private static final By VALID_OFFERS = By.cssSelector("button[data-cy='valid-offers-stateless']");
    private static final By INVOICE_VALIDATION = By.cssSelector("button[data-cy='invoice-validation']");

    private static final By ERROR_ALERT = By.id("sas-alert");
    private static final By ERROR_MESSAGE = By.cssSelector(".error-message");

    private final Map<String, LocalDateTime> accountsInUse = new ConcurrentHashMap<>();


    // Mapping des offres - CORRECTION selon la base de données
    private static final Map<String, String> OFFRE_MAP = Map.of(
            "ACCESS", "75W1AC|ACDD",
            "EVASION", "75W2EV|EVDD",
            "ACCESS+", "75W4ACP|ACPDD",
            "TOUT CANAL+", "75W6TCA|TCADD"
    );

    // Mapping des options pour le système Canal+ (pour les selects)
    // Mise à jour du mapping des options
    private static final Map<String, String> OPTION_CANAL_MAP = Map.of(
            "CHARME", "CHR",
            "ENGLISH", "ENGLISH",
            "ENGLISH_CHANNELS", "ENGLISH",  // Ajoutez cette ligne
            "PVR", "PVRDD",
            "2ECRANS", "2ECDD",
            "NETFLIX1", "NFX1SMDD",
            "NETFLIX2", "NFX2SMDD",
            "NETFLIX4", "NFX4SMDD"
    );


    // Ajouter ces mappings dans les constantes de classe
    private static final Map<String, String> OFFRE_SPECIFIC_OPTIONS = Map.of(
            "EVASION", "EAOEVDD",
            "ACCESS+", "EAOACPDD"
    );

    private static final Map<String, String> ENGLISH_OPTION_MAP = Map.of(
            "EVASION", "EAOEVDD",      // EVASION + ENGLISH → EAOEVDD
            "ACCESS+", "EAOACPDD",      // ACCESS+ + ENGLISH → EAOACPDD
            "ACCESS", "",               // ACCESS n'a pas d'option ENGLISH
            "TOUT CANAL+", ""           // TOUT CANAL+ n'a pas d'option ENGLISH
    );

    @PostConstruct
    public void init() {
        // Pré-créer drivers au démarrage - Augmenté pour VPS Elite
        if (chromeRemoteEnabled) {
            CompletableFuture.runAsync(() -> {
                for (int i = 0; i < 5; i++) { // 5 drivers au lieu de 2
                    try {
                        WebDriver driver = createOptimizedWebDriver();
                        if (driver != null) {
                            driverPool.offer(driver);
                            log.info("✅ Driver #{} pré-créé et ajouté au pool", i + 1);
                        }
                    } catch (Exception e) {
                        log.error("Erreur création driver initial", e);
                    }
                }
            });
        }
        // Maintenance du pool toutes les 30 secondes
        poolMaintainer.scheduleAtFixedRate(this::maintainDriverPool, 30, 30, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void cleanup() {
        poolMaintainer.shutdown();
        smsExecutor.shutdown();
        driverPool.forEach(driver -> {
            try { driver.quit(); } catch (Exception ignored) {}
        });
    }

    private void maintainDriverPool() {
        Iterator<WebDriver> it = driverPool.iterator();
        while (it.hasNext()) {
            WebDriver driver = it.next();
            try {
                driver.getTitle();
            } catch (Exception e) {
                try { driver.quit(); } catch (Exception ignored) {}
                it.remove();
                log.info("🧹 Driver supprimé du pool");
            }
        }
    }

    private WebDriver getOrCreateDriver() throws InterruptedException {
        WebDriver driver = driverPool.poll(2, TimeUnit.SECONDS);
        if (driver != null) {
            try {
                driver.getTitle();
                log.info("♻️ Réutilisation d'un driver du pool");
                return driver;
            } catch (Exception e) {
                log.warn("Driver du pool invalide, création d'un nouveau");
                try { driver.quit(); } catch (Exception ignored) {}
            }
        }
        return createOptimizedWebDriver();
    }

    private WebDriver createOptimizedWebDriver() {
        try {
            ChromeOptions options = getOptimizedChromeOptions();

            if (chromeRemoteEnabled && seleniumRemoteUrl != null && !seleniumRemoteUrl.isEmpty()) {
                log.info("🚦 Utilisation de RemoteWebDriver via {}", seleniumRemoteUrl);
                return new RemoteWebDriver(
                        new URL(seleniumRemoteUrl),
                        options
                );
            } else {
                log.info("💻 Utilisation de ChromeDriver local");
                WebDriverManager.chromedriver().setup();
                ChromeDriver driver = new ChromeDriver(options);
                configureDriver(driver);
                return driver;
            }
        } catch (Exception e) {
            log.error("Erreur création driver : {}", e.getMessage(), e);
            return null;
        }
    }

    private ChromeOptions getOptimizedChromeOptions() {
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless=new");
        options.addArguments("--no-sandbox");
        options.addArguments("--disable-dev-shm-usage");
        options.addArguments("--disable-gpu");
        options.addArguments("--disable-web-security");
        options.addArguments("--window-size=1920,1080");
        options.addArguments("--disable-blink-features=AutomationControlled");

        // Optimisations pour VPS Elite
        options.addArguments("--memory-pressure-off");
        options.addArguments("--max_old_space_size=4096");

        // Désactive images/plugins pour accélérer
        Map<String, Object> prefs = new HashMap<>();
        prefs.put("profile.default_content_setting_values.images", 2);
        prefs.put("profile.default_content_setting_values.plugins", 2);
        prefs.put("profile.default_content_setting_values.popups", 2);
        prefs.put("profile.default_content_setting_values.geolocation", 2);
        prefs.put("profile.default_content_setting_values.notifications", 2);
        prefs.put("profile.default_content_setting_values.media_stream", 2);
        options.setExperimentalOption("prefs", prefs);

        return options;
    }

    private void configureDriver(WebDriver driver) {
        driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(3));
        driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(60));
        driver.manage().timeouts().scriptTimeout(Duration.ofSeconds(10));
    }

    @Override
    public String effectuerReabonnement(ReabonnementRequest req)
    {
        long startTime = System.currentTimeMillis();

        // ⭐ Indiquer le mode de fonctionnement
        if (paymentTestMode) {
            log.warn("🧪 ============================================");
            log.warn("🧪 MODE TEST ACTIVÉ - PAIEMENTS SIMULÉS");
            log.warn("🧪 ============================================");
        }

        log.info("🚀 DÉBUT RÉABONNEMENT pour abonné {} [Mode: {}]",
                req.getNumAbonne(),
                paymentTestMode ? "TEST" : "PRODUCTION");

        log.info("🚀 DÉBUT RÉABONNEMENT pour abonné {}", req.getNumAbonne());

        // Variables déclarées au niveau méthode
        WebDriver driver = null;
        JavascriptExecutor js = null;
        WebDriverWait wait = null;
        TransactionDto transaction = null;
        AccessDto currentAccount = null; // Pour tracker le compte utilisé

        // Variables de résultat
        boolean needReturn = false;
        String montantFinal = "N/A";
        String subscriberPhone = null;
        LocalDate subscriptionStartDate = null;
        LocalDate subscriptionEndDate = null;
        long processingDuration = 0;

        // SLACK: Notification de début
        slackService.sendReabonnementProgress("START",
                String.format("Démarrage réabonnement - Décodeur: %s, Offre: %s %s, Option: %s",
                        req.getNumAbonne(), req.getOffre(), req.getDuree(),
                        req.getOption() != null ? req.getOption() : "SANS_OPTION"));

        try {
            // ========== PHASE 1: INITIALISATION ==========
            driver = getOrCreateDriver();
            if (driver == null) {
                slackService.sendReabonnementError(req, "DRIVER_ERROR",
                        "Service temporairement indisponible - Impossible de démarrer le navigateur");
                return "Erreur : Impossible de démarrer le navigateur";
            }

            js = (JavascriptExecutor) driver;
            wait = new WebDriverWait(driver, Duration.ofSeconds(10));

            // ========== PHASE 2: LOGIN AVEC GESTION DES COMPTES ==========
            slackService.sendReabonnementProgress("LOGIN", "Connexion au système Canal+...");

            // Nouvelle gestion du login avec blocage du compte
            currentAccount = performFastLoginWithAccountBlocking(driver, js);
            if (currentAccount == null) {
                slackService.sendReabonnementError(req, "NO_ACCOUNT_AVAILABLE",
                        "Aucun compte Canal+ disponible");
                return "Erreur : Aucun compte Canal+ disponible actuellement. Veuillez réessayer dans quelques instants.";
            }

            // ========== PHASE 3: RECHERCHE ABONNÉ ==========
            slackService.sendSearchingDecoder(req.getNumAbonne());
            boolean searchSuccess = performRobustSearch(driver, js, wait, req.getNumAbonne());
            slackService.sendDecoderFound(req.getNumAbonne(), searchSuccess);

            if (!searchSuccess) {
                slackService.sendReabonnementError(req, "DECODER_NOT_FOUND",
                        "Abonné " + req.getNumAbonne() + " introuvable");

                transaction = createTransaction(req, montantFinal, null, null,
                        "failed", null, System.currentTimeMillis() - startTime);
                transaction.setErrorMessage("DECODER_NOT_FOUND");
                saveTransaction(transaction);

                return "Erreur : Abonné " + req.getNumAbonne() + " introuvable";
            }

            // ========== PHASE 4: CONFIGURATION ==========
            slackService.sendReabonnementProgress("SELECTION",
                    String.format("Configuration: %s - %s - %s",
                            req.getOffre(), req.getDuree(), req.getOption()));

            performFastSelection(driver, js, wait, req);

            // PHASE 5: EXTRACTION DONNÉES (AVANT validation!)
            log.info("📱 Extraction du numéro de téléphone de l'abonné...");
            subscriberPhone = extractSubscriberPhone(driver, js);

            // MODIFICATION: Gestion améliorée du numéro manquant
            if (subscriberPhone == null || subscriberPhone.isEmpty()) {
                log.error("❌ Numéro de téléphone non trouvé pour le décodeur {}", req.getNumAbonne());

                // Si on a un numéro fourni dans la requête, on l'utilise
                if (req.getPhoneNumber() != null && !req.getPhoneNumber().isEmpty()) {
                    subscriberPhone = cleanSubscriberPhone(req.getPhoneNumber());
                    log.info("✅ Utilisation du numéro fourni dans la requête: {}", subscriberPhone);

                    // Envoyer un SMS pour informer de la mise à jour nécessaire
                    sendPhoneUpdateNotification(req);

                } else {
                    // Pas de numéro trouvé ni fourni - envoyer alerte et continuer sans SMS
                    sendPhoneNotFoundAlert(req);
                    log.warn("⚠️ Réabonnement continuera sans envoi de SMS à l'abonné");

                    slackService.sendReabonnementProgress("PHONE_NOT_FOUND",
                            "Numéro non trouvé - Continuation sans SMS");

                    // On continue le processus sans lever d'exception
                    subscriberPhone = null;
                }
            } else {
                log.info("✅ Numéro de téléphone trouvé: {}", subscriberPhone);
                slackService.sendReabonnementProgress("EXTRACTION_TELEPHONE",
                        "Numéro abonné: " + subscriberPhone);
            }

            // Extraction montant/dates
            log.info("💰 Extraction des données de facture...");
            Map<String, Object> invoiceData = extractInvoiceData(driver, js);
            montantFinal = (String) invoiceData.get("montant");
            subscriptionStartDate = (LocalDate) invoiceData.get("dateDebut");
            subscriptionEndDate = (LocalDate) invoiceData.get("dateFin");

            if (!montantFinal.equals("N/A")) {
                slackService.sendReabonnementProgress("MONTANT", "Montant: " + montantFinal);
            }

            // PHASE 6: VALIDATION (APRÈS extraction!)
            slackService.sendReabonnementProgress("VALIDATION", "Validation en cours...");

            boolean validationSuccess = performValidationWithConfirmation(driver, js, wait);

            // Vérification immédiate des erreurs (payment mean géré automatiquement dans checkForErrors)
            String immediateErrorCheck = checkForErrors(driver, wait);
            if (immediateErrorCheck != null) {
                log.error("❌ ERREUR DÉTECTÉE: {}", immediateErrorCheck);

                transaction = createTransaction(req, montantFinal,
                        subscriptionStartDate, subscriptionEndDate,
                        "failed", null, System.currentTimeMillis() - startTime);
                transaction.setErrorMessage("VALIDATION_ERROR: " + immediateErrorCheck);
                saveTransaction(transaction);

                slackService.sendReabonnementError(req, "VALIDATION_ERROR",
                        "Erreur de validation - " + immediateErrorCheck);

                needReturn = false;
                return "ERREUR: Échec de validation. " + immediateErrorCheck;
            }

            // Vérification du succès de validation (après vérification erreur immédiate)
            if (!validationSuccess) {
                String errorCheck = checkForErrors(driver, wait);
                if (errorCheck != null) {
                    slackService.sendValidationStatus("ERROR", errorCheck);
                    throw new RuntimeException(errorCheck);
                } else {
                    slackService.sendValidationStatus("ERROR", "Aucune confirmation reçue");
                    throw new RuntimeException("Échec de la validation - Aucune confirmation reçue");
                }
            }

            // ========== PHASE 7: SUCCÈS - ENREGISTREMENT ==========

            processingDuration = System.currentTimeMillis() - startTime;
//            log.info("🎉 Réabonnement CONFIRMÉ avec succès en {}ms", processingDuration);

//            slackService.sendValidationStatus("SUCCESS",
//                    String.format("Confirmé en %dms", processingDuration));
//
//            transaction = createTransaction(req, montantFinal,
//                    subscriptionStartDate, subscriptionEndDate,
//                    "completed", null, processingDuration);

            if (paymentTestMode) {
                log.info("🧪 [TEST] Réabonnement SIMULÉ avec succès en {}ms", processingDuration);

                slackService.sendValidationStatus("TEST_SUCCESS",
                        String.format("SIMULATION confirmée en %dms", processingDuration));

                // Créer une transaction de test
                transaction = createTransaction(req, montantFinal,
                        subscriptionStartDate, subscriptionEndDate,
                        "test_completed", null, processingDuration);
                transaction.setErrorMessage("TEST_MODE");

            } else {
                log.info("🎉 Réabonnement CONFIRMÉ avec succès en {}ms", processingDuration);

                slackService.sendValidationStatus("SUCCESS",
                        String.format("Confirmé en %dms", processingDuration));

                transaction = createTransaction(req, montantFinal,
                        subscriptionStartDate, subscriptionEndDate,
                        "completed", null, processingDuration);
            }

            saveTransaction(transaction);

            slackService.sendReabonnementSuccess(req, montantFinal,
                    transaction.getReferenceNumber());

            // SMS si numéro disponible
            if (subscriberPhone != null && !subscriberPhone.isEmpty()) {
//                sendSmsSuccessToSubscriber(subscriberPhone, req, montantFinal, transaction);
                if (paymentTestMode) {
                    log.info("🧪 [TEST] SMS de succès NON envoyé (mode test)");
                } else {
                    sendSmsSuccessToSubscriber(subscriberPhone, req, montantFinal, transaction);
                }
            }

            needReturn = true;
            if (paymentTestMode) {
                return "🧪 [TEST] Réabonnement SIMULÉ avec succès ! (Aucun paiement réel effectué)";
            } else {
                return "Réabonnement effectué avec succès !";
            }

        } catch (SubscriberPhoneNotFoundException e) {
            log.error("❌ Numéro de téléphone non trouvé: {}", e.getMessage());
            slackService.sendReabonnementError(req, "PHONE_NOT_FOUND", e.getMessage());
            needReturn = false;
            return "Erreur : Numéro de téléphone de l'abonné non trouvé. " +
                    "Le réabonnement ne peut pas être effectué sans le numéro de l'abonné.";

        } catch (Exception e) {
            log.error("Erreur réabonnement", e);

            String errorMsg = e.getMessage() != null ? e.getMessage() : "Erreur inconnue";

            // [RESTE DE LA GESTION DES ERREURS INCHANGÉE]
            // CAS 1: ERREUR OPTION/PAYMENT MEAN
            if (errorMsg.contains("OPTION_NON_SELECTIONNEE") ||
                    errorMsg.contains("PAYMENT_MEAN_ERROR")) {

                log.error("❌ Erreur de configuration - Réabonnement annulé");

                transaction = createTransaction(req, montantFinal,
                        subscriptionStartDate, subscriptionEndDate,
                        "failed", null, System.currentTimeMillis() - startTime);
                transaction.setErrorMessage("CONFIG_ERROR: " + errorMsg);
                saveTransaction(transaction);

                slackService.sendReabonnementError(req, "CONFIG_ERROR",
                        "Configuration incorrecte");

                needReturn = false;
                return "ERREUR: Configuration incorrecte. Veuillez réessayer.";
            }
            // CAS 2: Solde insuffisant
            if (errorMsg.contains("SOLDE_INSUFFISANT") || errorMsg.contains("DTA-1009")) {
                log.warn("⚠️ Solde insuffisant pour l'abonné {}", req.getNumAbonne());

                slackService.sendReabonnementError(req, "SOLDE_INSUFFISANT",
                        "Solde insuffisant sur le compte distributeur Canal+");

                transaction = createTransaction(req, montantFinal,
                        subscriptionStartDate, subscriptionEndDate,
                        "failed", null, System.currentTimeMillis() - startTime);
                transaction.setErrorMessage("SOLDE_INSUFFISANT");
                saveTransaction(transaction);

                needReturn = false;
                return "Erreur : Solde insuffisant sur votre compte distributeur. " +
                        "Veuillez recharger votre compte.";
            }

            // CAS 3: Timeout avec message spécifique
            if (errorMsg.contains("Aucune confirmation de paiement après validation")) {

                log.error("❌ ÉCHEC: Timeout sans confirmation de paiement");

                slackService.sendReabonnementError(req, "TIMEOUT_NO_PAYMENT_CONFIRMATION",
                        "Timeout sans confirmation de paiement - Réabonnement échoué");

                transaction = createTransaction(req, montantFinal,
                        subscriptionStartDate, subscriptionEndDate,
                        "failed", null, System.currentTimeMillis() - startTime);
                transaction.setErrorMessage("TIMEOUT_NO_PAYMENT_CONFIRMATION");
                saveTransaction(transaction);

                needReturn = false;
                return "Erreur : Aucune confirmation de paiement reçue. " +
                        "Le réabonnement n'a pas été effectué. " +
                        "Vérifiez le statut dans votre compte Canal+ ou contactez le support au 622459305.";
            }

            // CAS 4: Autres timeouts génériques
            if (errorMsg.contains("Aucune confirmation reçue") ||
                    errorMsg.contains("timeout") ||
                    errorMsg.contains("Timeout")) {

                log.error("❌ Timeout détecté - Traité comme échec par sécurité");

                slackService.sendReabonnementError(req, "TIMEOUT_ERROR", errorMsg);

                transaction = createTransaction(req, montantFinal,
                        subscriptionStartDate, subscriptionEndDate,
                        "failed", null, System.currentTimeMillis() - startTime);
                transaction.setErrorMessage("TIMEOUT_ERROR: " + errorMsg);
                saveTransaction(transaction);

                needReturn = false;
                return "Erreur technique : Timeout. " +
                        "Vérifiez le statut de l'abonnement ou contactez le support au 622459305.";
            }

            // CAS 5: Abonné introuvable
            if (errorMsg.contains("introuvable")) {
                slackService.sendReabonnementError(req, "DECODER_NOT_FOUND", errorMsg);

                transaction = createTransaction(req, montantFinal,
                        subscriptionStartDate, subscriptionEndDate,
                        "failed", null, System.currentTimeMillis() - startTime);
                transaction.setErrorMessage("DECODER_NOT_FOUND");
                saveTransaction(transaction);

                needReturn = false;
                return "Erreur : " + errorMsg;
            }

            // CAS 6: Erreur DTA
            if (errorMsg.contains("ERREUR_DTA") || errorMsg.contains("DTA-")) {
                slackService.sendReabonnementError(req, "ERREUR_DTA", errorMsg);

                transaction = createTransaction(req, montantFinal,
                        subscriptionStartDate, subscriptionEndDate,
                        "failed", null, System.currentTimeMillis() - startTime);
                transaction.setErrorMessage("ERREUR_DTA: " + errorMsg);
                saveTransaction(transaction);

                needReturn = false;
                return "Erreur : " + errorMsg;
            }

            // CAS 7: Erreur ERROR générique
            if (errorMsg.contains("ERROR")) {
                slackService.sendReabonnementError(req, "ERREUR_SYSTEME", errorMsg);

                transaction = createTransaction(req, montantFinal,
                        subscriptionStartDate, subscriptionEndDate,
                        "failed", null, System.currentTimeMillis() - startTime);
                transaction.setErrorMessage("ERREUR_SYSTEME: " + errorMsg);
                saveTransaction(transaction);

                needReturn = false;
                return "Erreur système : " + errorMsg + ". " +
                        "Veuillez réessayer ou contactez le support au 622459305.";
            }

            // CAS GÉNÉRAL: Erreur technique
            log.error("❌ Erreur technique: {}", errorMsg);

            slackService.sendReabonnementError(req, "ERREUR_TECHNIQUE", errorMsg);

            transaction = createTransaction(req, montantFinal,
                    subscriptionStartDate, subscriptionEndDate,
                    "failed", null, System.currentTimeMillis() - startTime);
            transaction.setErrorMessage("ERREUR_TECHNIQUE: " + errorMsg);
            saveTransaction(transaction);

            needReturn = false;
            return "Erreur technique - Veuillez réessayer. " +
                    "Si le problème persiste, contactez le support au 622459305.";

        } finally {
            // LIBÉRATION DU COMPTE
            if (currentAccount != null) {
                releaseAccount(currentAccount.getUsername());
                log.info("🔓 Compte {} libéré", currentAccount.getUsername());
            }

            // Nettoyage des ressources
            if (driver != null) {
                try {
                    driver.manage().deleteAllCookies();
                    driver.get("about:blank");

                    if (needReturn && driverPool.size() < 5) {
                        driverPool.offer(driver);
                        log.info("♻️ Driver retourné au pool");
                    } else {
                        driver.quit();
                        log.info("🗑️ Driver fermé");
                    }
                } catch (Exception cleanupEx) {
                    log.warn("⚠️ Erreur nettoyage: {}", cleanupEx.getMessage());
                    try {
                        driver.quit();
                    } catch (Exception ignored) {}
                }
            }

            log.info("⏱️ Durée totale: {}ms", System.currentTimeMillis() - startTime);
        }
    }


    private AccessDto performFastLoginWithAccountBlocking(WebDriver driver, JavascriptExecutor js) {
        int maxAttempts = 5;
        Set<String> failedAccounts = new HashSet<>();

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                // Récupérer un compte disponible et non bloqué
                AccessDto accessDto = getNextAvailableAccountWithLocking(failedAccounts);

                if (accessDto == null) {
                    log.error("❌ Plus aucun compte Canal+ disponible après {} tentatives", attempt - 1);
                    return null;
                }

                log.info("🔐 Tentative #{} - Login avec le compte: {}", attempt, accessDto.getUsername());

                // Tenter la connexion
                driver.get("https://cgaweb-afrique.canal-plus.com/mypos/");

                WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(15));
                WebElement loginInput = wait.until(ExpectedConditions.presenceOfElementLocated(LOGIN_INPUT));
                WebElement passwordInput = wait.until(ExpectedConditions.presenceOfElementLocated(PASSWORD_INPUT));

                loginInput.clear();
                loginInput.sendKeys(accessDto.getUsername());
                passwordInput.clear();
                passwordInput.sendKeys(accessDto.getPassword());
                passwordInput.sendKeys(Keys.RETURN);

                try {
                    wait.withTimeout(Duration.ofSeconds(15)).until(ExpectedConditions.or(
                            ExpectedConditions.urlContains("dashboard"),
                            ExpectedConditions.urlContains("search-subscriber"),
                            ExpectedConditions.presenceOfElementLocated(SUBSCRIBER_INPUT)
                    ));

                    String currentUrl = driver.getCurrentUrl();
                    if (currentUrl.contains("dashboard") ||
                            currentUrl.contains("search-subscriber") ||
                            driver.findElements(SUBSCRIBER_INPUT).size() > 0) {

                        log.info("✅ Login successful avec {}", accessDto.getUsername());
                        updateAccountLastUsed(accessDto);
                        return accessDto; // Succès, retourner le compte utilisé
                    }

                } catch (TimeoutException e) {
                    log.warn("⚠️ Échec de connexion pour le compte {} - Probable 2FA activé",
                            accessDto.getUsername());

                    // Libérer le compte
                    releaseAccount(accessDto.getUsername());

                    // Ajouter à la liste des échecs
                    failedAccounts.add(accessDto.getUsername());

                    // Envoyer SMS d'alerte 2FA amélioré
                    send2FAAlertSMSEnhanced(accessDto.getUsername());

                    if (attempt < maxAttempts) {
                        log.info("🔄 Passage au compte suivant...");
                        Thread.sleep(2000);
                    }
                }

            } catch (Exception e) {
                log.error("Erreur lors de la tentative de connexion #{}: {}", attempt, e.getMessage());
            }
        }

        return null;
    }

    private AccessDto getNextAvailableAccountWithLocking(Set<String> failedAccounts) {
        try {
            List<AccessDto> activeAccounts = reabonnementRepository.findAllActiveAccess();

            // Filtrer les comptes disponibles
            List<AccessDto> availableAccounts = activeAccounts.stream()
                    .filter(account -> !failedAccounts.contains(account.getUsername()))
                    .filter(account -> !isAccountInUse(account.getUsername()))
                    .filter(account -> !isAccountTemporarilyBlocked(account.getUsername()))
                    .sorted((a, b) -> {
                        if (a.getLastUsedAt() == null) return -1;
                        if (b.getLastUsedAt() == null) return 1;
                        return a.getLastUsedAt().compareTo(b.getLastUsedAt());
                    })
                    .collect(Collectors.toList());

            if (availableAccounts.isEmpty()) {
                return null;
            }

            // Prendre le premier compte disponible et le verrouiller
            AccessDto selectedAccount = availableAccounts.get(0);
            lockAccount(selectedAccount.getUsername());

            return selectedAccount;

        } catch (Exception e) {
            log.error("Erreur lors de la récupération des comptes: {}", e.getMessage());
            return null;
        }
    }


    private boolean isAccountInUse(String username) {
        LocalDateTime lockedUntil = accountsInUse.get(username);
        if (lockedUntil != null) {
            if (LocalDateTime.now().isBefore(lockedUntil)) {
                return true;
            } else {
                // Le verrou a expiré (sécurité après 10 minutes)
                accountsInUse.remove(username);
            }
        }
        return false;
    }


    // Verrouiller un compte
    private void lockAccount(String username) {
        // Verrouillage pour 10 minutes maximum (sécurité)
        accountsInUse.put(username, LocalDateTime.now().plusMinutes(10));
        log.info("🔒 Compte {} verrouillé pour utilisation", username);
    }

    // Libérer un compte
    private void releaseAccount(String username) {
        accountsInUse.remove(username);
        log.info("🔓 Compte {} libéré", username);
    }

    // SMS pour numéro de téléphone non trouvé
    private void sendPhoneNotFoundAlert(ReabonnementRequest req) {
        smsExecutor.execute(() -> {
            try {
                TokenResponse token = orangeSmsService.getOAuthToken();
                if (!isTokenValid(token)) {
                    log.error("❌ Token invalide pour envoi SMS alerte");
                    return;
                }

                String message = String.format(
                        "⚠️ ALERTE CANAL+\n\n" +
                                "Numéro de téléphone non trouvé lors du réabonnement:\n\n" +
                                "Décodeur: %s\n" +
                                "Numéro fourni: %s\n\n" +
                                "Action requise: Mettre à jour le numéro dans CGA Canal+\n\n" +
                                "Heure: %s",
                        req.getNumAbonne(),
                        req.getPhoneNumber() != null ? req.getPhoneNumber() : "AUCUN",
                        LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
                );

                sendSmsToAdmins(token.getToken(), message);

            } catch (Exception e) {
                log.error("Erreur envoi SMS alerte numéro non trouvé: {}", e.getMessage());
            }
        });
    }



    // SMS pour mise à jour du numéro nécessaire
    private void sendPhoneUpdateNotification(ReabonnementRequest req) {
        smsExecutor.execute(() -> {
            try {
                TokenResponse token = orangeSmsService.getOAuthToken();
                if (!isTokenValid(token)) {
                    log.error("❌ Token invalide pour envoi SMS");
                    return;
                }

                String message = String.format(
                        "📱 MISE À JOUR REQUISE\n\n" +
                                "Décodeur: %s\n" +
                                "Numéro utilisé: %s\n\n" +
                                "Ce numéro a été fourni manuellement.\n" +
                                "Veuillez le mettre à jour dans CGA Canal+.\n\n" +
                                "Heure: %s",
                        req.getNumAbonne(),
                        req.getPhoneNumber(),
                        LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
                );

                sendSmsToAdmins(token.getToken(), message);

            } catch (Exception e) {
                log.error("Erreur envoi SMS notification: {}", e.getMessage());
            }
        });
    }


    // SMS d'alerte 2FA amélioré
    private void send2FAAlertSMSEnhanced(String accountUsername) {
        smsExecutor.execute(() -> {
            try {
                TokenResponse token = orangeSmsService.getOAuthToken();
                if (!isTokenValid(token)) {
                    log.error("❌ Token invalide pour envoi SMS alerte 2FA");
                    return;
                }

                String message = String.format(
                        "🔐 ALERTE 2FA CANAL+\n\n" +
                                "Compte bloqué par authentification double facteur:\n\n" +
                                "Username: %s\n\n" +
                                "ACTION URGENTE:\n" +
                                "1. Connectez-vous à CGA Canal+\n" +
                                "2. Utilisez Google Authenticator\n" +
                                "3. Désactivez le 2FA pour ce compte\n\n" +
                                "Heure: %s",
                        accountUsername,
                        LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
                );

                sendSmsToAdmins(token.getToken(), message);

            } catch (Exception e) {
                log.error("Erreur envoi SMS alerte 2FA: {}", e.getMessage());
            }
        });
    }

    // Méthode helper pour envoyer aux admins
    private void sendSmsToAdmins(String token, String message) {
        String[] adminNumbers = {"+224622459305", "+224621091895", "+224623761847"};

        for (String number : adminNumbers) {
            try {
                orangeSmsService.sendSms(token, number, senderName, message);
                log.info("📱 SMS envoyé à {}", number);
                Thread.sleep(500); // Pause entre les envois
            } catch (Exception e) {
                log.error("Erreur envoi SMS à {}: {}", number, e.getMessage());
            }
        }
    }

    // Méthode scheduled pour nettoyer les comptes verrouillés expirés
    @Scheduled(fixedDelay = 300000) // Toutes les 5 minutes
    private void cleanupLockedAccounts() {
        int beforeSize = accountsInUse.size();
        accountsInUse.entrySet().removeIf(entry ->
                LocalDateTime.now().isAfter(entry.getValue())
        );
        int removed = beforeSize - accountsInUse.size();
        if (removed > 0) {
            log.info("🧹 Nettoyage comptes verrouillés: {} comptes libérés", removed);
        }
    }





    // Nouvelle méthode pour créer une transaction sans user_id
    private TransactionDto createTransaction(ReabonnementRequest req,
                                             String montant, LocalDate startDate,
                                             LocalDate endDate, String status,
                                             String canalUsername, long processingDuration) {

        // S'assurer que le décodeur existe
        ensureDecoderExists(req.getNumAbonne());

        return TransactionDto.builder()
                .decoderNumber(req.getNumAbonne())
                .packageId(mapPackageId(req.getOffre()))
                .languageOptionId(mapOptionId(req.getOption()))
                .durationId(mapDurationId(req.getDuree()))
                .amountGnf(extractNumericAmount(montant))
                .transactionDate(LocalDateTime.now())
                .status(status)
                .paymentMethod("DISTRIBUTEUR")
                .referenceNumber(generateReferenceNumber())
                .subscriptionStartDate(startDate)
                .subscriptionEndDate(endDate)
                .canalUsername(canalUsername)
                .processingDurationMs((int) processingDuration)
                .errorMessage(null) // Sera défini plus tard si erreur
                .build();
    }

    // Méthode pour sauvegarder la transaction
    private void saveTransaction(TransactionDto transaction) {
        if (transaction == null) {
            log.warn("⚠️ Transaction null, pas d'enregistrement");
            return;
        }

        try {
            reabonnementRepository.addTransaction(transaction);

            log.info("✅ Transaction enregistrée: {} - {} - {} GNF - Référence: {}",
                    transaction.getDecoderNumber(),
                    transaction.getStatus(),
                    transaction.getAmountGnf(),
                    transaction.getReferenceNumber());

        } catch (Exception e) {
            log.error("❌ Erreur lors de l'enregistrement de la transaction: {}", e.getMessage());
        }
    }

    // Méthode pour s'assurer que le décodeur existe
    private void ensureDecoderExists(String decoderNumber) {
        if (decoderNumber == null || decoderNumber.trim().isEmpty()) {
            log.warn("⚠️ Numéro de décodeur vide");
            return;
        }

        try {
            String checkSql = "SELECT COUNT(*) FROM decoders WHERE decoder_number = ?";
            Integer count = jdbcTemplate.queryForObject(checkSql, Integer.class, decoderNumber);

            if (count == null || count == 0) {
                String insertSql = """
                INSERT INTO decoders (decoder_number, status, installation_date) 
                VALUES (?, 'active', CURRENT_DATE) 
                ON CONFLICT (decoder_number) DO NOTHING
                """;

                jdbcTemplate.update(insertSql, decoderNumber);
                log.info("✅ Nouveau décodeur créé: {}", decoderNumber);
            }
        } catch (Exception e) {
            log.error("❌ Erreur vérification/création décodeur: {}", e.getMessage());
        }
    }

    // Générer un numéro de référence unique
    private String generateReferenceNumber() {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
        String timestamp = LocalDateTime.now().format(formatter);
        String random = UUID.randomUUID().toString().substring(0, 4).toUpperCase();
        return String.format("REB-%s-%s", timestamp, random);
    }

    // SMS de succès pour l'abonné
    private void sendSmsSuccessToSubscriber(String subscriberPhone, ReabonnementRequest req,
                                            String montant, TransactionDto transaction) {
        smsExecutor.execute(() -> {
            try {
                TokenResponse token = orangeSmsService.getOAuthToken();
                if (!isTokenValid(token)) {
                    log.error("❌ Token invalide pour envoi SMS");
                    return;
                }

                // Formater le reçu
                DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy à HH:mm");
                String dateFormatted = transaction.getTransactionDate().format(dateFormatter);
                String montantFormatted = String.format("+%,d GNF", transaction.getAmountGnf())
                        .replace(",", " ");

                String message = String.format(
                        "🧾 REÇU IO REABO\n\n" +
                                "Montant: %s\n" +
                                "Type: %s\n" +
                                "Date: %s\n" +
                                "Statut: Effectuée\n" +
                                "Décodeur: %s\n" +
                                "Référence: %s\n\n" +
                                "Merci!",
                        montantFormatted,
                        req.getOffre().toUpperCase(),
                        dateFormatted,
                        req.getNumAbonne(),
                        transaction.getReferenceNumber()
                );

                orangeSmsService.sendSms(token.getToken(), subscriberPhone, senderName, message);
                log.info("📱 SMS Reçu envoyé à l'abonné {}", subscriberPhone);

            } catch (Exception e) {
                log.error("Erreur envoi SMS: {}", e.getMessage());
            }
        });
    }

    // Méthode de login modifiée pour accepter AccessDto
    private void performFastLoginWithAccount(WebDriver driver, JavascriptExecutor js, AccessDto account) {
        log.info("🔐 Login avec le compte: {}", account.getUsername());
        driver.get("https://cgaweb-afrique.canal-plus.com/mypos/");

        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(15));
        WebElement loginInput = wait.until(ExpectedConditions.presenceOfElementLocated(LOGIN_INPUT));
        WebElement passwordInput = wait.until(ExpectedConditions.presenceOfElementLocated(PASSWORD_INPUT));

        loginInput.clear();
        loginInput.sendKeys(account.getUsername());
        passwordInput.clear();
        passwordInput.sendKeys(account.getPassword());
        passwordInput.sendKeys(Keys.RETURN);

        try {
            wait.withTimeout(Duration.ofSeconds(15)).until(ExpectedConditions.or(
                    ExpectedConditions.urlContains("dashboard"),
                    ExpectedConditions.urlContains("search-subscriber"),
                    ExpectedConditions.presenceOfElementLocated(SUBSCRIBER_INPUT)
            ));
            log.info("✅ Login successful avec {}", account.getUsername());
        } catch (TimeoutException e) {
            throw new RuntimeException("Login timeout pour le compte " + account.getUsername());
        }
    }

    // Extraction du numéro de téléphone dans une méthode séparée
    // Extraction du numéro de téléphone
    private String extractSubscriberPhone(WebDriver driver, JavascriptExecutor js) {
        try {
            log.info("📱 Extraction du numéro de téléphone de l'abonné...");
            Thread.sleep(1000);

            String jsScript = """
            var phoneInput = document.querySelector("input[data-cy='phone']") ||
                           document.querySelector("input[name='MOBILE1']") ||
                           document.querySelector("input.customer-field-text[name='MOBILE1']");
            
            if (phoneInput && phoneInput.value) {
                return phoneInput.value;
            }
            
            var inputs = document.querySelectorAll('input[type="text"], input[type="tel"]');
            for (var i = 0; i < inputs.length; i++) {
                var value = inputs[i].value;
                if (value && (value.includes('224') || value.match(/00224-/) || value.match(/[67]\\d{8}/))) {
                    return value;
                }
            }
            
            return null;
            """;

            String phoneValue = (String) js.executeScript(jsScript);
            if (phoneValue != null && !phoneValue.trim().isEmpty()) {
                String cleanedPhone = cleanSubscriberPhone(phoneValue);
                log.info("✅ Numéro de l'abonné extrait: {}", cleanedPhone);
                return cleanedPhone;
            } else {
                log.warn("⚠️ Numéro de l'abonné non trouvé");
            }
        } catch (Exception e) {
            log.error("Erreur extraction numéro: {}", e.getMessage());
        }
        return null;
    }


    // Extraire le montant numérique
    private Integer extractNumericAmount(String montantText) {
        if (montantText == null || montantText.equals("N/A")) {
            return 0;
        }

        try {
            String cleanAmount = montantText.replaceAll("[^0-9]", "");
            if (!cleanAmount.isEmpty()) {
                return Integer.parseInt(cleanAmount);
            }
        } catch (Exception e) {
            log.error("Erreur extraction montant de '{}': {}", montantText, e.getMessage());
        }

        return 0;
    }

    // Extraire toutes les données de la facture
    private Map<String, Object> extractInvoiceData(WebDriver driver, JavascriptExecutor js) {
        Map<String, Object> data = new HashMap<>();
        data.put("montant", "N/A");
        data.put("dateDebut", null);
        data.put("dateFin", null);

        try {
            String jsScript = """
            var result = {};
            
            // Extraire le montant
            var montantElements = document.querySelectorAll('.invoice-price-amount');
            if (montantElements.length > 0) {
                result.montant = montantElements[0].textContent.trim();
            }
            
            // Extraire les dates depuis la structure HTML
            var infoElements = document.querySelectorAll('.ordinary, .ordinary-right');
            var dateDebut = null;
            var dateFin = null;
            
            for (var i = 0; i < infoElements.length; i++) {
                var text = infoElements[i].textContent.trim();
                if (text.includes('Date de début') && i + 1 < infoElements.length) {
                    dateDebut = infoElements[i + 1].textContent.trim();
                }
                if (text.includes('Date de fin') && i + 1 < infoElements.length) {
                    dateFin = infoElements[i + 1].textContent.trim();
                }
            }
            
            // Alternative: chercher avec les spans
            var spans = document.querySelectorAll('span.ordinary-right');
            spans.forEach(function(span, index) {
                var prevElement = span.previousElementSibling;
                if (prevElement && prevElement.textContent.includes('Date de début')) {
                    dateDebut = span.textContent.trim();
                }
                if (prevElement && prevElement.textContent.includes('Date de fin')) {
                    dateFin = span.textContent.trim();
                }
            });
            
            result.dateDebut = dateDebut;
            result.dateFin = dateFin;
            
            return JSON.stringify(result);
            """;

            String resultJson = (String) js.executeScript(jsScript);
            if (resultJson != null) {
                if (resultJson.contains("montant")) {
                    String montant = extractJsonValue(resultJson, "montant");
                    data.put("montant", montant);
                }

                String dateDebutStr = extractJsonValue(resultJson, "dateDebut");
                String dateFinStr = extractJsonValue(resultJson, "dateFin");

                data.put("dateDebut", parseDate(dateDebutStr));
                data.put("dateFin", parseDate(dateFinStr));
            }

        } catch (Exception e) {
            log.error("Erreur extraction données facture: {}", e.getMessage());
        }

        return data;
    }

    // Parser une date au format DD/MM/YYYY
    private LocalDate parseDate(String dateStr) {
        if (dateStr == null || dateStr.isEmpty() || "null".equals(dateStr)) {
            return null;
        }

        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy");
            return LocalDate.parse(dateStr, formatter);
        } catch (Exception e) {
            log.error("Erreur parsing date '{}': {}", dateStr, e.getMessage());
            return null;
        }
    }


    // CORRECTION: Mapper les IDs selon votre base de données
    private String mapPackageId(String offre) {
        if (offre == null) return "access";

        return switch (offre.toUpperCase()) {
            case "ACCESS" -> "access";
            case "EVASION" -> "evasion";
            case "ACCESS+" -> "access_plus";
            case "TOUT CANAL+" -> "tout_canal";
            default -> "access";
        };
    }

    // CORRECTION: Mapper les options selon la base de données
    private String mapOptionId(String option) {
        if (option == null || option.isEmpty() || "SANS_OPTION".equalsIgnoreCase(option)) {
            return "sans_option";
        }

        return switch (option.toUpperCase()) {
            case "CHARME", "CHR" -> "charme";
            case "ENGLISH", "ENGLISH PLUS", "ENGLISH_CHANNELS", "ENGLISH CHANNELS" -> "english";
            case "PVR", "PVRDD" -> "pvr";
            case "2ECRANS", "2ECDD", "2 ECRANS" -> "2ecrans";
            case "NETFLIX1", "NFX1SMDD", "NETFLIX 1", "NETFLIX 1 ECRAN" -> "netflix_1";
            case "NETFLIX2", "NFX2SMDD", "NFX2SHDD", "NETFLIX 2", "NETFLIX 2 ECRANS" -> "netflix_2";
            case "NETFLIX4", "NFX4SMDD", "NFX4SHDD", "NETFLIX 4", "NETFLIX 4 ECRANS" -> "netflix_4";
            default -> {
                log.warn("⚠️ Option non reconnue: '{}', utilisation de sans_option", option);
                yield "sans_option";
            }
        };
    }
    // CORRECTION: Mapper les durées selon la base de données
    private String mapDurationId(String duree) {
        if (duree == null) return "1_month";

        String cleaned = duree.toLowerCase().trim();

        if (cleaned.contains("1") && (cleaned.contains("mois") || cleaned.contains("month"))) return "1_month";
        if (cleaned.contains("3") && (cleaned.contains("mois") || cleaned.contains("month"))) return "3_months";
        if (cleaned.contains("6") && (cleaned.contains("mois") || cleaned.contains("month"))) return "6_months";
        if (cleaned.contains("12") && (cleaned.contains("mois") || cleaned.contains("month"))) return "12_months";
        if (cleaned.contains("1") && (cleaned.contains("an") || cleaned.contains("year"))) return "12_months";

        // Par défaut
        return "1_month";
    }

    // 3. Méthode de validation des options
    private boolean isValidOption(String optionId) {
        Set<String> validOptions = Set.of(
                "english",
                "sans_option",
                "charme",
                "pvr",
                "2ecrans",
                "netflix_1",
                "netflix_2",
                "netflix_4"
        );

        return validOptions.contains(optionId);
    }

    // Méthode helper pour nettoyer le numéro de téléphone
    private String cleanSubscriberPhone(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.trim().isEmpty()) {
            return null;
        }

        log.debug("🔧 Nettoyage du numéro: '{}'", phoneNumber);

        String cleaned = phoneNumber.replaceAll("[^\\d+]", "");

        if (cleaned.startsWith("00224")) {
            cleaned = "+224" + cleaned.substring(5);
        } else if (cleaned.startsWith("224") && cleaned.length() == 12) {
            cleaned = "+" + cleaned;
        } else if (cleaned.length() == 9 && (cleaned.startsWith("6") || cleaned.startsWith("7"))) {
            cleaned = "+224" + cleaned;
        } else if (cleaned.startsWith("+224") && cleaned.length() == 13) {
            // Déjà au bon format
        } else {
            log.warn("⚠️ Format de numéro non reconnu: '{}', utilisation tel quel", phoneNumber);
            return phoneNumber;
        }

        log.info("✅ Numéro nettoyé: '{}'", cleaned);
        return cleaned;
    }

    // Méthode performFastSelection mise à jour
    private void performFastSelection(WebDriver driver, JavascriptExecutor js,
                                      WebDriverWait wait, ReabonnementRequest req) {
        log.info("⚡ Sélection rapide pour {} - {} - {}", req.getOffre(), req.getDuree(), req.getOption());

        try {
            // 1. Cliquer sur le bouton de sélection
            WebElement selectBtn = wait.until(ExpectedConditions.elementToBeClickable(SELECT_SUBSCRIBER));
            selectBtn.click();
            log.info("✅ Clicked select button");
            Thread.sleep(500);

            // 2. Les étapes suivantes
            try {
                WebElement validBtn = wait.until(ExpectedConditions.elementToBeClickable(SUBSCRIBER_VALID));
                validBtn.click();
                log.info("✅ Clicked subscriber-valid");
                Thread.sleep(500);
            } catch (TimeoutException e) {
                log.warn("⚠️ Bouton subscriber-valid non trouvé, tentative de continuer...");
            }

            try {
                WebElement renewalBtn = wait.until(ExpectedConditions.elementToBeClickable(RENEWAL_QUICK));
                renewalBtn.click();
                log.info("✅ Clicked renewal-quick");
            } catch (TimeoutException e) {
                log.warn("⚠️ Bouton renewal-quick non trouvé");
            }

            // 3. Attendre le formulaire de modification
            log.info("⏳ Attente du formulaire de modification...");
            Thread.sleep(2000);

            // Chercher le formulaire
            WebElement durationSelect = wait.until(ExpectedConditions.presenceOfElementLocated(
                    By.cssSelector("select[name='duration']")
            ));

            // 4. Préparer les valeurs à sélectionner
            String offreValue = OFFRE_MAP.getOrDefault(req.getOffre().toUpperCase(), req.getOffre());

            // Déterminer l'option à utiliser
            String optionValue = null;

            // CAS SPÉCIAL : ENGLISH
            if ("ENGLISH".equalsIgnoreCase(req.getOption()) ||
                    "ENGLISH_CHANNELS".equalsIgnoreCase(req.getOption())) {

                String englishMapping = ENGLISH_OPTION_MAP.get(req.getOffre().toUpperCase());

                if (englishMapping != null && !englishMapping.isEmpty()) {
                    optionValue = englishMapping;
                    log.info("🌐 Mapping ENGLISH → {} pour l'offre {}", optionValue, req.getOffre());
                } else {
                    log.warn("⚠️ L'offre {} ne supporte pas l'option ENGLISH", req.getOffre());
                    // Pour ACCESS et TOUT CANAL+, on ne sélectionne aucune option
                    optionValue = null;
                }
            }
            // CAS NORMAL : autres options
            else if (req.getOption() != null && !req.getOption().isEmpty() &&
                    !"SANS_OPTION".equalsIgnoreCase(req.getOption())) {

                // Utiliser le mapping standard pour les autres options
                optionValue = OPTION_CANAL_MAP.getOrDefault(req.getOption().toUpperCase(), null);

                if (optionValue == null) {
                    log.warn("⚠️ Option {} non reconnue dans le mapping standard", req.getOption());
                    // On essaiera quand même de la sélectionner telle quelle
                    optionValue = req.getOption();
                } else {
                    log.info("📝 Option standard mappée: {} → {}", req.getOption(), optionValue);
                }
            }
            // CAS SANS OPTION
            else {
                log.info("ℹ️ Aucune option demandée (SANS_OPTION ou vide)");
                optionValue = null;
            }

            // Log complet avant sélection
            log.info("📋 Configuration finale:");
            log.info("   - Offre: {} → {}", req.getOffre(), offreValue);
            log.info("   - Durée: {}", req.getDuree());
            log.info("   - Option demandée: {}", req.getOption());
            log.info("   - Option Canal+ à sélectionner: {}", optionValue != null ? optionValue : "AUCUNE");

            // 5. Effectuer les sélections avec la nouvelle méthode améliorée
            performDropdownSelections(driver, wait, durationSelect, req, offreValue, optionValue);

            // 6. Attendre un peu pour s'assurer que tout est bien sélectionné
            Thread.sleep(500);

            // 7. Valider les sélections
            WebElement validButton = wait.until(ExpectedConditions.elementToBeClickable(VALID_OFFERS));
            js.executeScript("arguments[0].scrollIntoView(true);", validButton);
            Thread.sleep(200);

            // Capturer l'état avant validation pour debug
            try {
                   String formState = (String) js.executeScript("""
                    var form = document.querySelector('.form-border');
                    if (!form) return 'Form not found';
                    
                    var duration = form.querySelector('select[name="duration"]');
                    var offer = form.querySelector('select[name="offer"]');
                    var option = form.querySelector('select[name="option"]');
                    
                    return JSON.stringify({
                        duration: duration ? duration.value + ' = ' + duration.options[duration.selectedIndex].text : 'N/A',
                        offer: offer ? offer.value + ' = ' + offer.options[offer.selectedIndex].text : 'N/A',
                        option: option ? option.value + ' = ' + option.options[option.selectedIndex].text : 'N/A'
                    });
                """);
                    log.info("📸 État du formulaire avant validation: {}", formState);
                } catch (Exception e) {
                    log.debug("Impossible de capturer l'état du formulaire");
                }

            // ⭐ AJOUTER CES LIGNES CRITIQUES:
            try {
                validButton.click();
                log.info("✅ Bouton valid-offers cliqué");
            } catch (Exception e) {
                // Si le clic échoue, essayer avec JavaScript
                js.executeScript("arguments[0].click();", validButton);
                log.info("✅ Bouton valid-offers cliqué (via JavaScript)");
            }

           // Attendre que la validation soit prise en compte
            Thread.sleep(1500);

        } catch (Exception e) {
            log.error("Erreur critique dans performFastSelection", e);
            throw new RuntimeException("Échec de la sélection: " + e.getMessage(), e);
        }
    }
    // Méthode helper pour obtenir le code Canal+ pour ENGLISH selon l'offre
    private String getEnglishOptionCodeForOffer(String offer) {
        return ENGLISH_OPTION_MAP.getOrDefault(offer.toUpperCase(), "");
    }

    // Méthode helper pour les sélections dropdown
    private void performDropdownSelections(WebDriver driver, WebDriverWait wait,
                                           WebElement durationSelect, ReabonnementRequest req,
                                           String offreValue, String optionValue) {
        try {
            // 1. SÉLECTION DE LA DURÉE (inchangé)
            Select durationDropdown = new Select(durationSelect);

            log.info("Options durée disponibles: {}",
                    durationDropdown.getOptions().stream()
                            .map(WebElement::getText)
                            .collect(Collectors.toList())
            );

            boolean found = false;
            for (WebElement option : durationDropdown.getOptions()) {
                String optionText = option.getText();
                if (optionText.contains(req.getDuree()) ||
                        (req.getDuree().contains("1") && optionText.contains("1 mois"))) {
                    durationDropdown.selectByVisibleText(optionText);
                    found = true;
                    log.info("✅ Durée sélectionnée: {}", optionText);
                    break;
                }
            }

            if (!found) {
                log.warn("⚠️ Durée non trouvée, sélection par défaut");
                durationDropdown.selectByIndex(1);
            }

            Thread.sleep(500);

            // 2. SÉLECTION DE L'OFFRE (inchangé)
            WebElement offerSelect = wait.until(ExpectedConditions.presenceOfElementLocated(
                    By.cssSelector("select[name='offer']")
            ));
            Select offerDropdown = new Select(offerSelect);

            try {
                log.info("🎯 Tentative de sélection de l'offre: {} avec valeur: {}", req.getOffre(), offreValue);
                offerDropdown.selectByValue(offreValue);
                log.info("✅ Offre sélectionnée par valeur: {}", offreValue);
                Thread.sleep(1500);

            } catch (Exception e) {
                log.warn("⚠️ Erreur sélection offre par valeur, tentative par texte");
                boolean offerFound = false;
                for (WebElement option : offerDropdown.getOptions()) {
                    if (option.getText().toUpperCase().contains(req.getOffre().toUpperCase())) {
                        offerDropdown.selectByVisibleText(option.getText());
                        offerFound = true;
                        log.info("✅ Offre sélectionnée par texte: {}", option.getText());
                        Thread.sleep(1000);
                        break;
                    }
                }
                if (!offerFound && offerDropdown.getOptions().size() > 1) {
                    offerDropdown.selectByIndex(1);
                    log.warn("⚠️ Offre par défaut sélectionnée");
                }
            }

            // 3. SÉLECTION DE L'OPTION - PARTIE CORRIGÉE POUR SANS_OPTION
            Thread.sleep(500);

            try {
                WebElement optionSelect = wait.until(ExpectedConditions.presenceOfElementLocated(
                        By.cssSelector("select[name='option']")
                ));

                if (optionSelect.isDisplayed()) {
                    wait.until(ExpectedConditions.elementToBeClickable(optionSelect));
                    Select optionDropdown = new Select(optionSelect);

                    // Logger les options disponibles
                    List<String> availableOptions = optionDropdown.getOptions().stream()
                            .map(o -> o.getAttribute("value") + "=" + o.getText())
                            .collect(Collectors.toList());
                    log.info("📋 Options disponibles après sélection de {}: {}", req.getOffre(), availableOptions);

                    // VÉRIFIER SI SANS_OPTION EST DEMANDÉ
                    String requestedOption = req.getOption();
                    boolean isSansOption = requestedOption == null ||
                            requestedOption.isEmpty() ||
                            requestedOption.equalsIgnoreCase("SANS_OPTION") ||
                            requestedOption.equalsIgnoreCase("SANS OPTION") ||
                            requestedOption.equalsIgnoreCase("AUCUNE");

                    if (isSansOption) {
                        // CAS SPÉCIAL : SANS_OPTION - GARDER "Choisir..." sélectionné
                        log.info("✅ SANS_OPTION demandé - Maintien de l'option par défaut 'Choisir...'");

                        // S'assurer que "Choisir..." est sélectionné
                        boolean choisirFound = false;

                        // Méthode 1: Chercher "Choisir..." explicitement
                        for (int i = 0; i < optionDropdown.getOptions().size(); i++) {
                            WebElement opt = optionDropdown.getOptions().get(i);
                            String optText = opt.getText();
                            String optVal = opt.getAttribute("value");

                            if (optText.toLowerCase().contains("choisir") ||
                                    optText.toLowerCase().contains("choose") ||
                                    (optVal != null && optVal.isEmpty())) {

                                optionDropdown.selectByIndex(i);
                                choisirFound = true;
                                log.info("✅ Option 'Choisir...' sélectionnée à l'index {}", i);
                                break;
                            }
                        }

                        // Méthode 2: Si pas trouvé, sélectionner l'index 0 (généralement "Choisir...")
                        if (!choisirFound) {
                            try {
                                optionDropdown.selectByIndex(0);
                                log.info("✅ Option index 0 sélectionnée (présumé 'Choisir...')");
                            } catch (Exception e) {
                                log.warn("⚠️ Impossible de sélectionner l'index 0");
                            }
                        }

                        // Vérification finale pour SANS_OPTION
                        Thread.sleep(300);
                        WebElement selectedOption = optionDropdown.getFirstSelectedOption();
                        String selectedText = selectedOption.getText();
                        String selectedValue = selectedOption.getAttribute("value");

                        log.info("📸 SANS_OPTION - Option finale: '{}' (value='{}')", selectedText, selectedValue);

                        // Si on n'est PAS sur "Choisir..." ou équivalent, forcer avec JavaScript
                        if (!selectedText.toLowerCase().contains("choisir") &&
                                !selectedText.toLowerCase().contains("choose") &&
                                selectedValue != null && !selectedValue.isEmpty()) {

                            log.warn("⚠️ Correction nécessaire - Forçage de 'Choisir...' via JavaScript");

                            JavascriptExecutor js = (JavascriptExecutor) driver;
                            js.executeScript(
                                    "var select = arguments[0];" +
                                            "select.value = '';" +
                                            "select.selectedIndex = 0;" +
                                            "var event = new Event('change', { bubbles: true });" +
                                            "select.dispatchEvent(event);",
                                    optionSelect
                            );

                            Thread.sleep(300);
                            log.info("✅ Forcé à 'Choisir...' via JavaScript pour SANS_OPTION");
                        }

                    } else if (optionValue != null && !optionValue.isEmpty()) {
                        // CAS NORMAL : Une option spécifique est demandée (CHR, ENGLISH, etc.)
                        log.info("🎯 Sélection de l'option spécifique: {}", optionValue);

                        boolean optionSelected = false;

                        // Essayer par valeur
                        try {
                            optionDropdown.selectByValue(optionValue);
                            optionSelected = true;
                            log.info("✅ Option sélectionnée par valeur: {}", optionValue);
                        } catch (Exception e) {
                            // Essayer par texte
                            for (WebElement opt : optionDropdown.getOptions()) {
                                String optText = opt.getText().toUpperCase();
                                String optVal = opt.getAttribute("value");

                                if (optVal.equals(optionValue) || optText.contains(optionValue.toUpperCase())) {
                                    optionDropdown.selectByVisibleText(opt.getText());
                                    optionSelected = true;
                                    log.info("✅ Option sélectionnée par texte: {}", opt.getText());
                                    break;
                                }
                            }
                        }

                        if (!optionSelected) {
                            log.warn("⚠️ Option '{}' non trouvée dans la liste", optionValue);
                        }
                    }

                    // Log final de l'option sélectionnée
                    Thread.sleep(200);
                    WebElement finalSelectedOption = optionDropdown.getFirstSelectedOption();
                    log.info("✅ OPTION FINALE CONFIRMÉE: '{}' (value='{}')",
                            finalSelectedOption.getText(),
                            finalSelectedOption.getAttribute("value"));
                }

            } catch (Exception e) {
                log.error("❌ Erreur lors de la sélection de l'option: {}", e.getMessage());
                // Ne pas faire échouer le processus si l'option est optionnelle
            }

            // 4. VÉRIFICATION FINALE DE L'OFFRE (inchangé)
            Thread.sleep(500);
            try {
                WebElement offerSelectFinal = driver.findElement(By.cssSelector("select[name='offer']"));
                Select offerDropdownFinal = new Select(offerSelectFinal);
                String selectedValue = offerDropdownFinal.getFirstSelectedOption().getAttribute("value");
                String selectedText = offerDropdownFinal.getFirstSelectedOption().getText();

                log.info("🔍 Vérification finale - Offre sélectionnée: {} ({})", selectedText, selectedValue);

                if (!selectedValue.equals(offreValue) && !selectedText.toUpperCase().contains(req.getOffre().toUpperCase())) {
                    log.error("❌ INCOHÉRENCE DÉTECTÉE: L'offre sélectionnée ne correspond pas!");
                    log.error("   Attendu: {} ({})", req.getOffre(), offreValue);
                    log.error("   Obtenu: {} ({})", selectedText, selectedValue);

                    log.info("🔄 Nouvelle tentative de sélection de l'offre...");
                    offerDropdownFinal.selectByValue(offreValue);
                    Thread.sleep(500);
                }
            } catch (Exception e) {
                log.error("Erreur lors de la vérification finale: {}", e.getMessage());
            }

        } catch (Exception e) {
            log.error("Erreur critique lors de la sélection des valeurs: {}", e.getMessage());
            throw new RuntimeException("Échec de la sélection des valeurs", e);
        }
    }


    private void performFastLogin(WebDriver driver, JavascriptExecutor js) {
        int maxAttempts = 5; // Nombre maximum de tentatives avec différents comptes
        AccessDto accessDto = null;
        boolean loginSuccessful = false;
        Set<String> failedAccounts = new HashSet<>(); // Pour éviter de réessayer un compte qui a échoué

        for (int attempt = 1; attempt <= maxAttempts && !loginSuccessful; attempt++) {
            try {
                // Récupérer un compte qui n'a pas encore échoué
                accessDto = getNextAvailableAccount(failedAccounts);

                if (accessDto == null) {
                    log.error("❌ Plus aucun compte Canal+ disponible après {} tentatives", attempt - 1);
                    throw new RuntimeException("Aucun compte Canal+ disponible");
                }

                log.info("🔐 Tentative #{} - Login avec le compte: {}", attempt, accessDto.getUsername());

                // Tenter la connexion
                driver.get("https://cgaweb-afrique.canal-plus.com/mypos/");

                WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(15));
                WebElement loginInput = wait.until(ExpectedConditions.presenceOfElementLocated(LOGIN_INPUT));
                WebElement passwordInput = wait.until(ExpectedConditions.presenceOfElementLocated(PASSWORD_INPUT));

                loginInput.clear();
                loginInput.sendKeys(accessDto.getUsername());
                passwordInput.clear();
                passwordInput.sendKeys(accessDto.getPassword());

                passwordInput.sendKeys(Keys.RETURN);

                try {
                    // Attendre la redirection après connexion
                    wait.withTimeout(Duration.ofSeconds(15)).until(ExpectedConditions.or(
                            ExpectedConditions.urlContains("dashboard"),
                            ExpectedConditions.urlContains("search-subscriber"),
                            ExpectedConditions.presenceOfElementLocated(SUBSCRIBER_INPUT)
                    ));

                    // Vérifier si on est bien connecté
                    String currentUrl = driver.getCurrentUrl();
                    if (currentUrl.contains("dashboard") ||
                            currentUrl.contains("search-subscriber") ||
                            driver.findElements(SUBSCRIBER_INPUT).size() > 0) {

                        log.info("✅ Login successful avec {}", accessDto.getUsername());
                        loginSuccessful = true;

                        // Mettre à jour la dernière utilisation du compte
                        updateAccountLastUsed(accessDto);

                    } else {
                        throw new TimeoutException("Page de connexion toujours affichée");
                    }

                } catch (TimeoutException e) {
                    log.warn("⚠️ Échec de connexion pour le compte {} - Probable 2FA activé", accessDto.getUsername());

                    // Ajouter le compte à la liste des échecs
                    failedAccounts.add(accessDto.getUsername());

                    // Envoyer SMS d'alerte pour activation 2FA
                    send2FAAlertSMS(accessDto.getUsername());

                    // Si ce n'est pas la dernière tentative, continuer avec un autre compte
                    if (attempt < maxAttempts) {
                        log.info("🔄 Passage au compte suivant...");
                        Thread.sleep(2000); // Petite pause avant la prochaine tentative
                    } else {
                        log.error("❌ Échec de connexion après {} tentatives", maxAttempts);
                        throw new RuntimeException("Impossible de se connecter après " + maxAttempts + " tentatives");
                    }
                }

            } catch (RuntimeException re) {
                throw re; // Relancer les RuntimeException
            } catch (Exception e) {
                log.error("Erreur lors de la tentative de connexion #{}: {}", attempt, e.getMessage());
                if (accessDto != null) {
                    failedAccounts.add(accessDto.getUsername());
                }
            }
        }

        if (!loginSuccessful) {
            throw new RuntimeException("Échec de connexion avec tous les comptes disponibles");
        }
    }
    private boolean performRobustSearch(WebDriver driver, JavascriptExecutor js,
                                        WebDriverWait wait, String numAbonne)
    {
        log.info("🔍 Recherche de l'abonné {}...", numAbonne);

        try {
            WebElement searchInput = wait.until(ExpectedConditions.presenceOfElementLocated(SUBSCRIBER_INPUT));
            WebElement searchButton = wait.until(ExpectedConditions.elementToBeClickable(SEARCH_BUTTON));

            searchInput.clear();
            Thread.sleep(200);
            searchInput.click();
            searchInput.sendKeys(numAbonne);
            Thread.sleep(200);

            log.info("✅ Numéro saisi: {}", numAbonne);

            searchButton.click();
            log.info("🔎 Clic sur recherche effectué");

            Thread.sleep(3000);

            // Vérifier les résultats
            List<By> resultSelectors = Arrays.asList(
                    By.cssSelector(".div-table-subscriber"),
                    By.cssSelector(".subscriber-pane"),
                    By.cssSelector("[data-cy='select-subscriber']")
            );

            for (By selector : resultSelectors) {
                List<WebElement> elements = driver.findElements(selector);
                if (!elements.isEmpty()) {
                    log.info("✅ Résultats trouvés avec sélecteur: {}", selector);
                    return true;
                }
            }

            // Vérifier les messages d'erreur
            List<WebElement> errorMessages = driver.findElements(By.xpath(
                    "//div[contains(@class,'error') or contains(text(),'Aucun') or contains(text(),'introuvable')]"
            ));

            for (WebElement error : errorMessages) {
                if (error.getText().toLowerCase().contains("aucun") ||
                        error.getText().toLowerCase().contains("introuvable")) {
                    log.error("❌ Abonné introuvable: {}", error.getText());
                    return false;
                }
            }

            return true;

        } catch (Exception e) {
            log.error("Erreur lors de la recherche : {}", e.getMessage());
            return false;
        }
    }


    // SOLUTION DÉFINITIVE - Version corrigée qui cherche le message au bon endroit
// SOLUTION DÉFINITIVE - Le PDF de facture comme preuve de succès

    private boolean performValidationWithConfirmation(WebDriver driver, JavascriptExecutor js, WebDriverWait wait) {
        try {
            log.info("📋 Début de la validation...");

            // ⭐ MODE TEST - SIMULATION COMPLÈTE
            if (paymentTestMode) {
                log.warn("🧪 ============================================");
                log.warn("🧪 MODE TEST ACTIVÉ - SIMULATION DE PAIEMENT");
                log.warn("🧪 ============================================");

                // Capturer l'état du formulaire même en mode test
                try {
                    String formState = (String) js.executeScript("""
                    var form = {};
                    var duration = document.querySelector('select[name="duration"]');
                    var offer = document.querySelector('select[name="offer"]');
                    var option = document.querySelector('select[name="option"]');
                    
                    if (duration) {
                        form.duration = duration.value + ' = ' + duration.options[duration.selectedIndex].text;
                    }
                    if (offer) {
                        form.offer = offer.value + ' = ' + offer.options[offer.selectedIndex].text;
                    }
                    if (option) {
                        form.option = option.value + ' = ' + option.options[option.selectedIndex].text;
                    }
                    return JSON.stringify(form);
                """);
                    log.info("🧪 [TEST] Configuration capturée: {}", formState);
                } catch (Exception e) {
                    log.debug("🧪 [TEST] Pas de formulaire à capturer");
                }

                // Simuler les étapes de validation
                log.info("🧪 [TEST] Étape 1: Recherche du bouton de validation...");
                Thread.sleep(500);

                log.info("🧪 [TEST] Étape 2: Clic simulé sur le bouton de validation");
                Thread.sleep(1000);

                log.info("🧪 [TEST] Étape 3: Attente de réponse du serveur Canal+...");
                Thread.sleep(1500);

                log.info("🧪 [TEST] Étape 4: Vérification du statut de paiement...");
                Thread.sleep(1000);

                log.info("🧪 [TEST] Étape 5: Génération de la facture PDF...");
                Thread.sleep(500);

                // Résultat de la simulation
                String simulatedInvoiceId = "TEST_" + System.currentTimeMillis();
                log.info("✅ [TEST] SIMULATION RÉUSSIE");
                log.info("🧪 [TEST] Status HTTP: 200 OK");
                log.info("🧪 [TEST] Facture simulée: /reports/frameset/{}.pdf", simulatedInvoiceId);
                log.info("🧪 [TEST] Montant débité: 0 GNF (simulation)");
                log.info("🧪 ============================================");

                // Notification Slack
                slackService.sendReabonnementProgress("TEST_MODE",
                        "⚠️ PAIEMENT SIMULÉ - Aucun débit réel effectué");

                // Retourner succès sans toucher au compte Canal+
                return true;
            }

            // ⭐ MODE PRODUCTION - VALIDATION RÉELLE
            log.info("💳 ============================================");
            log.info("💳 MODE PRODUCTION - VALIDATION RÉELLE");
            log.info("💳 ============================================");

            // Capturer l'état initial
            String urlBeforeValidation = driver.getCurrentUrl();
            int windowsBeforeValidation = driver.getWindowHandles().size();
            log.info("📍 État initial - URL: {}, Fenêtres: {}", urlBeforeValidation, windowsBeforeValidation);

            // Capturer l'état du formulaire
            try {
                String formState = (String) js.executeScript("""
                var form = {};
                var duration = document.querySelector('select[name="duration"]');
                var offer = document.querySelector('select[name="offer"]');
                var option = document.querySelector('select[name="option"]');
                
                if (duration) {
                    form.duration = duration.value + ' = ' + duration.options[duration.selectedIndex].text;
                }
                if (offer) {
                    form.offer = offer.value + ' = ' + offer.options[offer.selectedIndex].text;
                }
                if (option) {
                    form.option = option.value + ' = ' + option.options[option.selectedIndex].text;
                }
                return JSON.stringify(form);
            """);
                log.info("📸 État du formulaire avant validation: {}", formState);
            } catch (Exception e) {
                log.debug("Impossible de capturer l'état du formulaire: {}", e.getMessage());
            }

            // Trouver et cliquer sur le bouton de validation
            WebElement validationButton = findValidationButton(driver, wait);
            if (validationButton == null) {
                log.warn("⚠️ Aucun bouton de validation trouvé");
                return false;
            }

            js.executeScript("arguments[0].scrollIntoView({behavior: 'smooth', block: 'center'});", validationButton);
            Thread.sleep(500);

            try {
                validationButton.click();
                log.info("✅ Bouton de validation cliqué");
            } catch (Exception e) {
                js.executeScript("arguments[0].click();", validationButton);
                log.info("✅ Bouton de validation cliqué (JavaScript)");
            }

            // ATTENTE AVEC DÉTECTION PDF PRIORITAIRE
            log.info("⏳ Attente de confirmation (PDF ou message)...");

            int maxWaitSeconds = 20;
            boolean successConfirmed = false;
            boolean pdfConfirmed = false;
            boolean messageFound = false;
            String successMessage = null;

            for (int i = 0; i < maxWaitSeconds; i++) {
                Thread.sleep(1000);

                String currentUrl = driver.getCurrentUrl();

                // PRIORITÉ 1 : DÉTECTION DU PDF DE FACTURE
                if (!pdfConfirmed && driver.getWindowHandles().size() > windowsBeforeValidation) {
                    log.info("📄 Nouvel onglet détecté après {}s", i + 1);

                    String originalWindow = driver.getWindowHandle();
                    for (String handle : driver.getWindowHandles()) {
                        if (!handle.equals(originalWindow)) {
                            driver.switchTo().window(handle);
                            String pdfUrl = driver.getCurrentUrl();

                            if (pdfUrl.contains("/reports/frameset") ||
                                    pdfUrl.contains("cptr0030") ||
                                    pdfUrl.contains(".pdf")) {

                                log.info("✅ FACTURE PDF CONFIRMÉE: {}",
                                        pdfUrl.length() > 100 ? pdfUrl.substring(0, 100) + "..." : pdfUrl);
                                pdfConfirmed = true;
                                successConfirmed = true;

                                driver.close();
                                driver.switchTo().window(originalWindow);

                                log.info("🎉 SUCCÈS CONFIRMÉ PAR OUVERTURE DE LA FACTURE PDF");
                                break;
                            }

                            driver.switchTo().window(originalWindow);
                        }
                    }

                    if (pdfConfirmed) {
                        Thread.sleep(1000);
                        break;
                    }
                }

                // PRIORITÉ 2 : Message de succès
                if (!messageFound && !successConfirmed) {
                    try {
                        WebElement successDiv = driver.findElement(By.className("operation-achieved-div"));
                        if (successDiv.isDisplayed()) {
                            successMessage = successDiv.getText();
                            if (successMessage.contains("Le réabonnement a été fait avec succès")) {
                                log.info("🎉 Message de succès trouvé après {}s", i + 1);
                                messageFound = true;
                                successConfirmed = true;

                                try {
                                    WebElement continueBtn = driver.findElement(
                                            By.cssSelector("button[data-cy='continue-validation']")
                                    );
                                    if (continueBtn.isDisplayed()) {
                                        continueBtn.click();
                                        log.info("✅ Bouton 'Continuer' cliqué");
                                    }
                                } catch (Exception e) {
                                    // Ignorer
                                }

                                break;
                            }
                        }
                    } catch (Exception e) {
                        // Pas de message, continuer
                    }
                }

                // Vérifier les erreurs critiques
                try {
                    WebElement errorAlert = driver.findElement(By.id("sas-alert"));
                    if (errorAlert.isDisplayed()) {
                        String errorText = errorAlert.getText();

                        if (!errorText.toLowerCase().contains("payment mean") &&
                                !errorText.toLowerCase().contains("moyen de paiement")) {

                            if (errorText.contains("DTA-1009")) {
                                log.error("❌ Erreur: Solde insuffisant");
                                throw new RuntimeException("SOLDE_INSUFFISANT: " + errorText);
                            } else if (errorText.contains("DTA-")) {
                                log.error("❌ Erreur DTA: {}", errorText);
                                throw new RuntimeException("ERREUR_DTA: " + errorText);
                            }
                        }
                    }
                } catch (NoSuchElementException e) {
                    // Pas d'erreur
                }

                // Log de progression
                if ((i + 1) % 5 == 0) {
                    log.info("⏳ Attente... ({}s/{}s) - URL: {}, PDF: {}",
                            i + 1, maxWaitSeconds,
                            currentUrl.substring(currentUrl.lastIndexOf('/') + 1),
                            pdfConfirmed ? "✅" : "❌");
                }

                if (pdfConfirmed) {
                    log.info("✅ Validation confirmée par PDF après {}s", i + 1);
                    break;
                }
            }

            // ÉVALUATION FINALE
            if (successConfirmed) {
                if (pdfConfirmed) {
                    log.info("✅ PAIEMENT CONFIRMÉ PAR FACTURE PDF");
                } else if (messageFound) {
                    log.info("✅ PAIEMENT CONFIRMÉ PAR MESSAGE");
                }
                return true;
            }

            // Dernière vérification avant échec
            String finalUrl = driver.getCurrentUrl();

            if ((finalUrl.contains("/subscribers") || finalUrl.contains("/invoice")) &&
                    !finalUrl.equals(urlBeforeValidation)) {

                Boolean hasError = (Boolean) js.executeScript("""
                return !!document.querySelector('#sas-alert:not([style*="none"])') ||
                       document.body.innerText.toLowerCase().includes('error') ||
                       document.body.innerText.toLowerCase().includes('échec');
            """);

                if (!Boolean.TRUE.equals(hasError)) {
                    log.info("✅ SUCCÈS PRÉSUMÉ - Changement d'URL sans erreur");
                    return true;
                }
            }

            log.error("❌ Échec validation - Aucune confirmation après {}s", maxWaitSeconds);
            throw new RuntimeException("Aucune confirmation de paiement détectée");

        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            log.error("Erreur lors de la validation: {}", e.getMessage());
            return false;
        }
    }

    // Méthode helper pour vérifier la présence d'erreurs
    private boolean isErrorPresent(WebDriver driver) {
        try {
            // Vérifier plusieurs indicateurs d'erreur
            List<WebElement> errorElements = driver.findElements(By.xpath(
                    "//div[@id='sas-alert' and contains(@style,'display: block')] | " +
                            "//div[contains(@class,'error') and not(contains(@style,'display: none'))] | " +
                            "//span[contains(@class,'error-message')] | " +
                            "//div[contains(text(),'Erreur')] | " +
                            "//div[contains(text(),'ERROR')]"
            ));

            for (WebElement errorElem : errorElements) {
                if (errorElem.isDisplayed()) {
                    String errorText = errorElem.getText();
                    // Ignorer les erreurs "payment mean" temporaires
                    if (!errorText.toLowerCase().contains("payment mean") &&
                            !errorText.toLowerCase().contains("moyen de paiement")) {
                        log.debug("Erreur détectée: {}", errorText);
                        return true;
                    }
                }
            }

            // Vérifier aussi dans le texte de la page
            String pageText = driver.findElement(By.tagName("body")).getText().toLowerCase();
            if (pageText.contains("échec") ||
                    pageText.contains("erreur") && !pageText.contains("aucune erreur") ||
                    pageText.contains("failed") ||
                    pageText.contains("impossible")) {

                // Double vérification pour éviter les faux positifs
                if (!pageText.contains("succès") &&
                        !pageText.contains("effectué") &&
                        !pageText.contains("confirmé")) {
                    return true;
                }
            }

        } catch (Exception e) {
            log.debug("Erreur lors de la vérification d'erreurs: {}", e.getMessage());
        }

        return false;
    }


    // Ajouter aussi cette méthode helper pour un debug amélioré
    private void capturePageState(WebDriver driver, String phase) {
        try {
            log.debug("📸 Capture état - Phase: {}", phase);
            log.debug("  URL: {}", driver.getCurrentUrl());
            log.debug("  Title: {}", driver.getTitle());

            // Capturer les éléments importants
            List<WebElement> buttons = driver.findElements(By.tagName("button"));
            log.debug("  Nombre de boutons: {}", buttons.size());

            // Vérifier la présence de mots-clés importants
            String pageSource = driver.getPageSource().toLowerCase();
            log.debug("  Contient 'success': {}", pageSource.contains("success"));
            log.debug("  Contient 'error': {}", pageSource.contains("error"));
            log.debug("  Contient 'invoice': {}", pageSource.contains("invoice"));
            log.debug("  Contient 'confirmation': {}", pageSource.contains("confirmation"));

        } catch (Exception e) {
            log.debug("Erreur capture état: {}", e.getMessage());
        }
    }

    /**
     * Vérifie UNIQUEMENT les erreurs critiques (solde insuffisant, etc.)
     * IGNORE "payment mean" qui peut être temporaire
     */
    private String checkForCriticalErrors(WebDriver driver) {
        try {
            List<WebElement> errorAlerts = driver.findElements(ERROR_ALERT);
            if (!errorAlerts.isEmpty() && errorAlerts.get(0).isDisplayed()) {
                WebElement errorDiv = errorAlerts.get(0);
                WebElement errorMsg = errorDiv.findElement(ERROR_MESSAGE);
                String errorText = errorMsg.getText();

                // ⭐ IGNORER "payment mean" pendant la validation
                if (errorText.toLowerCase().contains("payment mean") ||
                        errorText.toLowerCase().contains("moyen de paiement")) {
                    return null; // Pas une erreur critique
                }

                // Vérifier les VRAIES erreurs critiques
                log.error("🚨 Erreur critique: {}", errorText);

                String errorCode = extractErrorCode(errorText);
                if ("DTA-1009".equals(errorCode)) {
                    return "SOLDE_INSUFFISANT: " + errorText;
                } else if (errorCode != null) {
                    return "ERREUR_DTA_" + errorCode + ": " + errorText;
                } else {
                    return "ERREUR_SYSTEME: " + errorText;
                }
            }
        } catch (Exception e) {
            // Ignorer les erreurs de vérification
        }
        return null;
    }

    /**
     * Vérifie les erreurs SAUF "payment mean" (qui peut apparaître temporairement)
     */
    private String checkForErrorsExcludingPaymentMean(WebDriver driver, WebDriverWait wait) {
        try {
            Thread.sleep(500);

            List<WebElement> errorAlerts = driver.findElements(ERROR_ALERT);
            if (!errorAlerts.isEmpty() && errorAlerts.get(0).isDisplayed()) {
                WebElement errorDiv = errorAlerts.get(0);
                WebElement errorMsg = errorDiv.findElement(ERROR_MESSAGE);
                String errorText = errorMsg.getText();

                // ⭐ IGNORER "payment mean" pendant la validation
                if (errorText.toLowerCase().contains("payment mean") ||
                        errorText.toLowerCase().contains("moyen de paiement")) {
                    log.debug("⚠️ Message 'payment mean' ignoré (temporaire)");
                    return null;
                }

                // Vérifier les autres erreurs critiques
                log.error("🚨 Erreur critique détectée: {}", errorText);

                String errorCode = extractErrorCode(errorText);
                if ("DTA-1009".equals(errorCode)) {
                    return "SOLDE_INSUFFISANT";
                } else if (errorCode != null) {
                    return "ERREUR_" + errorCode;
                } else {
                    return "ERREUR: " + errorText;
                }
            }
        } catch (Exception e) {
            log.debug("Pas d'erreur critique détectée: {}", e.getMessage());
        }
        return null;
    }

    private boolean verifyPaymentConfirmation(WebDriver driver, JavascriptExecutor js) {
        try {
            Thread.sleep(2000);

            // Vérifier le message exact de succès
            String confirmationScript = """
            var confirmed = false;
            
            // Chercher le message exact
            var bodyText = document.body.innerText;
            if (bodyText.includes('renewal of the contract was successfully done') ||
                bodyText.includes('réabonnement a été fait avec succès')) {
                confirmed = true;
            }
            
            // Vérifier qu'il n'y a PAS d'erreur
            var errorElements = document.querySelectorAll('#sas-alert, .error-message, [class*="error"]');
            for (var i = 0; i < errorElements.length; i++) {
                if (errorElements[i].style.display !== 'none' && 
                    errorElements[i].textContent.trim() !== '') {
                    confirmed = false;
                    break;
                }
            }
            
            return confirmed;
            """;

            Boolean confirmed = (Boolean) js.executeScript(confirmationScript);

            if (Boolean.TRUE.equals(confirmed)) {
                log.info("✅ Paiement confirmé par vérification supplémentaire");
                return true;
            } else {
                log.warn("⚠️ Aucune confirmation explicite de paiement trouvée");
                return false;
            }

        } catch (Exception e) {
            log.error("Erreur vérification paiement: {}", e.getMessage());
            return false;
        }
    }

    private ValidationResult waitForValidationResult(WebDriver driver, WebDriverWait wait) {
        final int MAX_WAIT_SECONDS = 15; // Réduit de 30 à 15 secondes
        JavascriptExecutor js = (JavascriptExecutor) driver;

        log.info("⏳ Attente du résultat (max {}s)...", MAX_WAIT_SECONDS);

        for (int i = 0; i < MAX_WAIT_SECONDS; i++) {
            try {
                Thread.sleep(1000);

                // 1. Vérifier les erreurs d'abord
                String errorCheck = checkForErrors(driver, wait);
                if (errorCheck != null) {
                    log.info("❌ Erreur détectée après {}s: {}", i + 1, errorCheck);
                    return new ValidationResult(ValidationStatus.ERROR, errorCheck, i + 1);
                }

                // 2. Vérifier le succès avec méthode améliorée
                String successCheck = checkForSuccessMessage(driver, js);
                if (successCheck != null) {
                    log.info("✅ Succès détecté après {}s: {}", i + 1, successCheck);
                    return new ValidationResult(ValidationStatus.SUCCESS, successCheck, i + 1);
                }

                // 3. Vérifier redirection vers facture
                String currentUrl = driver.getCurrentUrl();
                if (currentUrl.contains("reports/frameset") ||
                        currentUrl.contains("facture") ||
                        currentUrl.contains("invoice") ||
                        currentUrl.contains("confirmation") ||
                        currentUrl.contains("success")) {
                    log.info("✅ Redirection succès détectée après {}s: {}", i + 1, currentUrl);
                    return new ValidationResult(ValidationStatus.SUCCESS, "Redirection: " + currentUrl, i + 1);
                }

                // 4. Vérifier si le bouton continuer est visible (signe de succès)
                try {
                    WebElement continueBtn = driver.findElement(By.cssSelector("button[data-cy='continue-validation']"));
                    if (continueBtn.isDisplayed()) {
                        log.info("✅ Bouton Continuer visible après {}s", i + 1);
                        return new ValidationResult(ValidationStatus.SUCCESS, "Bouton Continuer visible", i + 1);
                    }
                } catch (Exception e) {
                    // Ignorer si pas trouvé
                }

                // 5. Vérifier si on n'est plus sur la page de validation (signe de succès)
                if (i > 5) { // Après 5 secondes
                    try {
                        // Si le bouton de validation n'est plus visible, c'est peut-être un succès
                        WebElement validationBtn = driver.findElement(By.cssSelector("button[data-cy='invoice-validation']"));
                        if (!validationBtn.isDisplayed()) {
                            log.info("⚠️ Bouton validation plus visible après {}s", i + 1);
                            // Continuer à chercher d'autres signes
                        }
                    } catch (Exception e) {
                        // Le bouton n'existe plus, probable succès
                        log.info("✅ Page validation terminée après {}s", i + 1);
                        return new ValidationResult(ValidationStatus.SUCCESS, "Page validation terminée", i + 1);
                    }
                }

                if (i % 3 == 0 && i > 0) {
                    log.debug("⏳ Attente... ({}s) - URL: {}", i, driver.getCurrentUrl());
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        // Avant de retourner TIMEOUT, faire une dernière vérification approfondie
        String finalUrl = driver.getCurrentUrl();
        if (!finalUrl.contains("mypos") && !finalUrl.contains("validation")) {
            log.info("✅ URL changée, probable succès: {}", finalUrl);
            return new ValidationResult(ValidationStatus.SUCCESS, "URL changée: " + finalUrl, MAX_WAIT_SECONDS);
        }

        return new ValidationResult(ValidationStatus.TIMEOUT,
                "Aucun résultat après " + MAX_WAIT_SECONDS + " secondes", MAX_WAIT_SECONDS);
    }

    private String checkForSuccessMessage(WebDriver driver, JavascriptExecutor js) {
        try {
            String result = (String) js.executeScript("""
            // Méthode 1: Message explicite UNIQUEMENT
            var successDiv = document.querySelector('.operation-achieved-div');
            if (successDiv) {
                var text = successDiv.textContent || successDiv.innerText || '';
                text = text.replace(/\\s+/g, ' ').trim();
                
                // PLUS STRICT: Vérifier le message exact
                if (text.includes('renewal of the contract was successfully done') || 
                    text.includes('réabonnement a été fait avec succès') ||
                    text.includes('Le réabonnement a été fait avec succès')) {
                    console.log('SUCCESS CONFIRMED:', text);
                    return text;
                }
            }
            
            // Méthode 2: Recherche large du message de succès
            var allElements = document.querySelectorAll('div, span, p');
            for (var i = 0; i < allElements.length; i++) {
                var text = (allElements[i].textContent || '').trim();
                if (text.includes('renewal of the contract was successfully done') ||
                    text.includes('réabonnement a été fait avec succès')) {
                    console.log('SUCCESS FOUND:', text);
                    return text;
                }
            }
            
            // NE PLUS RETOURNER SUCCESS_BY_URL
            // Seulement retourner null si pas de message explicite
            return null;
            """);

            return result;

        } catch (Exception e) {
            log.debug("Erreur vérification succès: {}", e.getMessage());
            return null;
        }
    }

    private boolean isSuccessMessageDisplayed(WebDriver driver) {
        try {
            JavascriptExecutor js = (JavascriptExecutor) driver;
            Boolean found = (Boolean) js.executeScript("""
            var checks = [
                () => {
                    var divs = document.getElementsByClassName('operation-achieved-div');
                    for (var i = 0; i < divs.length; i++) {
                        var text = (divs[i].textContent || '').toLowerCase();
                        if (text.includes('succès') || text.includes('réussi')) {
                            return true;
                        }
                    }
                    return false;
                },
                () => {
                    var form = document.querySelector('.customer-form');
                    if (form) {
                        var text = (form.textContent || '').toLowerCase();
                        return text.includes('réabonnement') && text.includes('succès');
                    }
                    return false;
                }
            ];
            
            for (var check of checks) {
                if (check()) return true;
            }
            
            return false;
            """);

            return Boolean.TRUE.equals(found);

        } catch (Exception e) {
            return false;
        }
    }

    private void clickContinueButton(WebDriver driver) {
        try {
            WebElement continueBtn = driver.findElement(By.cssSelector("button[data-cy='continue-validation']"));
            if (continueBtn.isDisplayed() && continueBtn.isEnabled()) {
                continueBtn.click();
                log.info("✅ Bouton 'Continuer' cliqué");
            }
        } catch (Exception e) {
            log.debug("Bouton Continuer non trouvé: {}", e.getMessage());
        }
    }

    private WebElement findValidationButton(WebDriver driver, WebDriverWait wait) {
        List<By> selectors = Arrays.asList(
                By.cssSelector("button[data-cy='invoice-validation']"),
                By.xpath("//button[contains(text(),'Valider')]"),
                By.xpath("//button[contains(text(),'Confirmer')]")
        );

        for (By selector : selectors) {
            try {
                WebElement button = wait.withTimeout(Duration.ofSeconds(2))
                        .until(ExpectedConditions.elementToBeClickable(selector));
                if (button != null && button.isDisplayed() && button.isEnabled()) {
                    log.info("✅ Bouton de validation trouvé: {}", selector);
                    return button;
                }
            } catch (TimeoutException e) {
                // Continuer
            }
        }

        return null;
    }

    private String extractAmount(WebDriver driver, JavascriptExecutor js) {
        try {
            String jsScript = """
            var amounts = document.querySelectorAll('.invoice-price-amount, .amount, .price, .total');
            for (var i = 0; i < amounts.length; i++) {
                var text = amounts[i].textContent.trim();
                if (text && text.match(/\\d+/)) {
                    return text;
                }
            }
            return 'N/A';
            """;

            String amount = (String) js.executeScript(jsScript);
            if (!amount.equals("N/A")) {
                log.info("💰 Montant trouvé: {}", amount);
                return amount;
            }

        } catch (Exception e) {
            log.error("Erreur extraction montant: {}", e.getMessage());
        }

        return "N/A";
    }

    // Version par défaut (sans paramètre) - pour compatibilité avec le code existant
    private String checkForErrors(WebDriver driver, WebDriverWait wait) {
        return checkForErrors(driver, wait, false); // Par défaut, ne pas ignorer payment mean
    }

    // Version complète avec paramètre ignorePaymentMean
    private String checkForErrors(WebDriver driver, WebDriverWait wait, boolean ignorePaymentMean) {
        try {
            Thread.sleep(1000);

            List<WebElement> errorAlerts = driver.findElements(ERROR_ALERT);
            if (!errorAlerts.isEmpty() && errorAlerts.get(0).isDisplayed()) {
                WebElement errorDiv = errorAlerts.get(0);
                WebElement errorMsg = errorDiv.findElement(ERROR_MESSAGE);
                String errorText = errorMsg.getText();

                log.error("🚨 Erreur détectée: {}", errorText);

                // GESTION ERREUR "PAYMENT MEAN"
                if (errorText.toLowerCase().contains("please select payment mean") ||
                        errorText.toLowerCase().contains("payment mean") ||
                        errorText.toLowerCase().contains("moyen de paiement")) {

                    // ⭐ Si on demande d'ignorer payment mean pendant la validation
                    if (ignorePaymentMean) {
                        log.warn("⚠️ Erreur 'payment mean' détectée mais IGNORÉE pendant la validation");
                        return null; // Ignorer complètement
                    }

                    log.error("❌ ERREUR PAYMENT MEAN - Vérification si c'est un faux positif");

                    // Vérifier si c'est un faux positif (succès malgré l'erreur)
                    Thread.sleep(3000);
                    String currentUrl = driver.getCurrentUrl();

                    if (!currentUrl.contains("search-subscriber") ||
                            currentUrl.contains("success") ||
                            currentUrl.contains("confirmation") ||
                            currentUrl.contains("reports")) {

                        log.info("✅ Succès détecté malgré l'erreur payment mean");
                        return null; // Ignorer l'erreur
                    }

                    // Si vraiment une erreur
                    log.error("❌ ERREUR PAYMENT MEAN CONFIRMÉE");
                    return "OPTION_NON_SELECTIONNEE: " + errorText;
                }

                // Gestion existante des autres erreurs
                String errorCode = extractErrorCode(errorText);

                if ("DTA-1009".equals(errorCode)) {
                    return "SOLDE_INSUFFISANT";
                } else if (errorCode != null) {
                    return "ERREUR_" + errorCode;
                } else {
                    return "ERREUR_INCONNUE";
                }
            }
        } catch (Exception e) {
            log.debug("Pas d'erreur détectée: {}", e.getMessage());
        }
        return null;
    }


    private String extractErrorCode(String errorText) {
        Pattern pattern = Pattern.compile("\\((DTA-\\d+)\\)");
        Matcher matcher = pattern.matcher(errorText);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private boolean isTokenValid(TokenResponse token) {
        return token != null &&
                token.getStatus() != null &&
                token.getStatus() == 200 &&
                token.getToken() != null &&
                !token.getToken().isEmpty();
    }

    @Override
    public Optional<Map<String, Object>> rechercherInfosAbonne(String numAbonne)
    {
        WebDriver driver = null;
        long startTime = System.currentTimeMillis();
        try {
            driver = getOrCreateDriver();
            if (driver == null) return Optional.empty();

            JavascriptExecutor js = (JavascriptExecutor) driver;
            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));

            performFastLogin(driver, js);

            WebElement searchInput = wait.until(ExpectedConditions.presenceOfElementLocated(SUBSCRIBER_INPUT));
            WebElement searchBtn = wait.until(ExpectedConditions.elementToBeClickable(SEARCH_BUTTON));

            searchInput.clear();
            searchInput.sendKeys(numAbonne);
            searchBtn.click();

            WebDriverWait resultWait = new WebDriverWait(driver, Duration.ofSeconds(8));
            resultWait.until(ExpectedConditions.or(
                    ExpectedConditions.presenceOfElementLocated(By.cssSelector(".div-table-subscriber .subscriber-pane")),
                    ExpectedConditions.presenceOfElementLocated(By.xpath("//div[contains(@class,'error-message')]"))
            ));

            List<WebElement> allSubscribers = driver.findElements(By.cssSelector(".div-table-subscriber .subscriber-pane"));

            if (allSubscribers.isEmpty()) {
                log.info("❌ Aucun résultat trouvé");
                return Optional.empty();
            }

            Map<String, Object> result = new HashMap<>();
            List<Map<String, String>> subscribersList = new ArrayList<>();

            log.info("📊 {} abonné(s) trouvé(s) pour {}", allSubscribers.size(), numAbonne);

            for (int i = 0; i < allSubscribers.size(); i++) {
                Map<String, String> abonneInfos = new LinkedHashMap<>();

                try {
                    WebElement subscriberPane = allSubscribers.get(i);

                    // 1. Nom et numéro de contrat
                    try {
                        WebElement nameEl = subscriberPane.findElement(By.className("subscriber-name"));
                        String nameText = nameEl.getText();
                        log.info("Nom complet trouvé: {}", nameText);

                        Pattern namePattern = Pattern.compile("(.+?)\\s*\\((\\d+/\\d+)\\)");
                        Matcher nameMatcher = namePattern.matcher(nameText);
                        if (nameMatcher.find()) {
                            abonneInfos.put("nom", nameMatcher.group(1).trim());
                            abonneInfos.put("numero_contrat", nameMatcher.group(2).trim());
                            String decoderNumber = nameMatcher.group(2).split("/")[0];
                            abonneInfos.put("decoder_number", decoderNumber);
                        }
                    } catch (Exception e) {
                        log.error("Erreur extraction nom: {}", e.getMessage());
                    }

                    // 2. Récupérer TOUT le HTML et le texte
                    String fullText = subscriberPane.getText();
                    String fullHtml = subscriberPane.getAttribute("innerHTML");
                    log.info("Texte complet de l'abonné {}: {}", i + 1, fullText);

                    // 3. Extraction via les éléments subscriber-simple
                    List<WebElement> simpleElements = subscriberPane.findElements(By.className("subscriber-simple"));
                    log.info("Nombre d'éléments subscriber-simple: {}", simpleElements.size());

                    for (int j = 0; j < simpleElements.size(); j++) {
                        WebElement elem = simpleElements.get(j);
                        String elemText = elem.getText().trim();
                        String elemHtml = elem.getAttribute("innerHTML");
                        log.info("Element {} texte: '{}', HTML: '{}'", j, elemText, elemHtml);

                        // STATUT
                        if (elemText.equalsIgnoreCase("Active")) {
                            abonneInfos.put("statut", "Active");
                        } else if (elemText.equalsIgnoreCase("Inactive")) {
                            abonneInfos.put("statut", "Inactive");
                        } else if (elemText.contains("ECHU") || elemText.contains("ANNULE")) {
                            abonneInfos.put("statut", "ECHU OU ANNULE");
                        }

                        // DATE DE FIN
                        if (!abonneInfos.containsKey("date_fin")) {
                            Pattern datePattern = Pattern.compile("(\\d{1,2}[/-]\\d{1,2}[/-]\\d{4})");
                            Matcher dateMatcher = datePattern.matcher(elemText);
                            if (dateMatcher.find()) {
                                String date = dateMatcher.group(1);
                                date = formatDate(date);
                                abonneInfos.put("date_fin", date);
                            }
                        }

                        // OFFRE - EXTRACTION MULTIPLE MÉTHODES
                        if (!abonneInfos.containsKey("offre")) {
                            // Méthode 1: Si l'élément contient "Offre Majeure"
                            if (elemHtml != null && elemHtml.contains("Offre Majeure")) {
                                // Extraire après </i>
                                Pattern p1 = Pattern.compile("</i>\\s*([^<]+)");
                                Matcher m1 = p1.matcher(elemHtml);
                                if (m1.find()) {
                                    String offre = m1.group(1).trim();
                                    if (!offre.isEmpty()) {
                                        abonneInfos.put("offre", offre);
                                        log.info("Offre trouvée méthode 1: {}", offre);
                                    }
                                }

                                // Si pas trouvé, essayer avec le texte
                                if (!abonneInfos.containsKey("offre")) {
                                    String offreText = elemText.replaceAll(".*Offre Majeure\\s*:\\s*", "").trim();
                                    if (!offreText.isEmpty() && !offreText.contains("Offre")) {
                                        abonneInfos.put("offre", offreText);
                                        log.info("Offre trouvée méthode 2: {}", offreText);
                                    }
                                }
                            }
                        }

                        // VILLE
                        if (!abonneInfos.containsKey("ville")) {
                            String[] villes = {"CONAKRY", "SIGUIRI", "KANKAN", "KINDIA", "LABE", "MAMOU", "FARANAH", "BOKE", "NZEREKORE"};
                            for (String ville : villes) {
                                if (elemText.contains(ville)) {
                                    abonneInfos.put("ville", ville);
                                    break;
                                }
                            }
                        }

                        // ADRESSE
                        if (!abonneInfos.containsKey("adresse") && elemText.matches(".*GCO\\d{4}.*")) {
                            abonneInfos.put("adresse", elemText);
                        }
                    }

                    // 4. EXTRACTION JAVASCRIPT POUR L'OFFRE SI TOUJOURS MANQUANTE
                    if (!abonneInfos.containsKey("offre")) {
                        log.info("Offre non trouvée, tentative extraction JavaScript complète");

                        String jsScript = """
                        try {
                            var container = arguments[0];
                            var result = {};
                            
                            // Méthode 1: Chercher dans tous les spans
                            var spans = container.querySelectorAll('.subscriber-simple');
                            for (var i = 0; i < spans.length; i++) {
                                var spanHtml = spans[i].innerHTML;
                                var spanText = spans[i].textContent || spans[i].innerText || '';
                                
                                if (spanHtml.includes('Offre Majeure')) {
                                    console.log('Span avec offre trouvé:', spanHtml);
                                    
                                    // Extraire après </i>
                                    var match = spanHtml.match(/<\\/i>\\s*([^<]+)/);
                                    if (match && match[1]) {
                                        result.offre = match[1].trim();
                                        console.log('Offre extraite:', result.offre);
                                        break;
                                    }
                                    
                                    // Sinon essayer avec le texte
                                    var textMatch = spanText.match(/Offre Majeure\\s*:\\s*(.+)/);
                                    if (textMatch && textMatch[1]) {
                                        result.offre = textMatch[1].trim();
                                        console.log('Offre extraite du texte:', result.offre);
                                        break;
                                    }
                                }
                            }
                            
                            // Méthode 2: Chercher dans tout le HTML
                            if (!result.offre) {
                                var fullHtml = container.innerHTML;
                                
                                // Chercher ACCESS+ d'abord
                                if (fullHtml.includes('ACCESS+')) {
                                    result.offre = 'ACCESS+';
                                } else if (fullHtml.includes('TOUT CANAL+')) {
                                    result.offre = 'TOUT CANAL+';
                                } else if (fullHtml.includes('EVASION+')) {
                                    result.offre = 'EVASION+';
                                } else if (fullHtml.includes('ACCESS')) {
                                    result.offre = 'ACCESS';
                                } else if (fullHtml.includes('EVASION')) {
                                    result.offre = 'EVASION';
                                }
                            }
                            
                            // Méthode 3: Recherche dans le texte complet
                            if (!result.offre) {
                                var fullText = container.textContent || container.innerText || '';
                                
                                // Liste des offres possibles (ordre important)
                                var offres = ['ACCESS+', 'TOUT CANAL+', 'EVASION+', 'ACCESS', 'EVASION'];
                                for (var j = 0; j < offres.length; j++) {
                                    if (fullText.includes(offres[j])) {
                                        result.offre = offres[j];
                                        break;
                                    }
                                }
                            }
                            
                            return JSON.stringify(result);
                        } catch(e) {
                            console.error('Erreur extraction offre:', e);
                            return '{}';
                        }
                        """;

                        try {
                            String jsResult = (String) js.executeScript(jsScript, subscriberPane);
                            if (jsResult != null && !jsResult.equals("{}")) {
                                String offre = extractJsonValue(jsResult, "offre");
                                if (offre != null && !offre.isEmpty()) {
                                    abonneInfos.put("offre", offre);
                                    log.info("Offre trouvée via JS: {}", offre);
                                }
                            }
                        } catch (Exception jsEx) {
                            log.error("Erreur JavaScript: {}", jsEx.getMessage());
                        }
                    }

                    // 5. RECHERCHE FINALE DANS LE HTML BRUT
                    if (!abonneInfos.containsKey("offre")) {
                        log.info("Tentative finale de recherche de l'offre dans le HTML brut");

                        // Rechercher directement les patterns d'offre
                        if (fullHtml.contains("ACCESS+")) {
                            abonneInfos.put("offre", "ACCESS+");
                        } else if (fullHtml.contains("TOUT CANAL+")) {
                            abonneInfos.put("offre", "TOUT CANAL+");
                        } else if (fullHtml.contains("EVASION+")) {
                            abonneInfos.put("offre", "EVASION+");
                        } else if (fullHtml.contains("ACCESS")) {
                            abonneInfos.put("offre", "ACCESS");
                        } else if (fullHtml.contains("EVASION")) {
                            abonneInfos.put("offre", "EVASION");
                        }

                        if (abonneInfos.containsKey("offre")) {
                            log.info("Offre trouvée dans HTML brut: {}", abonneInfos.get("offre"));
                        }
                    }

                    // 6. VALEUR PAR DÉFAUT SI VRAIMENT RIEN N'EST TROUVÉ
                    if (!abonneInfos.containsKey("offre")) {
                        log.warn("⚠️ Impossible de déterminer l'offre pour l'abonné {}", i + 1);
                        // Ne pas mettre de valeur par défaut, laisser vide
                    }

                    subscribersList.add(abonneInfos);

                    log.info("✅ Abonné {} extrait: Nom={}, Statut={}, Date={}, Offre={}, Ville={}",
                            i + 1,
                            abonneInfos.get("nom"),
                            abonneInfos.get("statut"),
                            abonneInfos.get("date_fin"),
                            abonneInfos.get("offre"),
                            abonneInfos.get("ville"));

                } catch (Exception e) {
                    log.error("Erreur extraction abonné {}: {}", i + 1, e.getMessage(), e);
                }
            }

            // Préparer le résultat final
            result.put("type_recherche", numAbonne.startsWith("00224") ? "TELEPHONE" : "DECODEUR");
            result.put("query", numAbonne);
            result.put("nombre_resultats", subscribersList.size());
            result.put("unique", subscribersList.size() == 1);
            result.put("multiple", subscribersList.size() > 1);
            result.put("resultats", subscribersList);
            result.put("dureeExecution", (System.currentTimeMillis() - startTime) + "ms");
            result.put("duree", (System.currentTimeMillis() - startTime) + "ms");
            result.put("existe", !subscribersList.isEmpty());
            result.put("source", "verification");
            result.put("message", subscribersList.size() + " abonné(s) trouvé(s)");

            if (subscribersList.size() == 1) {
                result.putAll(subscribersList.get(0));
            }

            log.info("📋 Résultats finaux: {}", result);

            return Optional.of(result);

        } catch (Exception e) {
            log.error("Erreur lors de la recherche : {}", e.getMessage(), e);
            return Optional.empty();
        } finally {
            if (driver != null) driverPool.offer(driver);
        }
    }

    @Override
    public List<PackageDto> getAllPackages() {
        return reabonnementRepository.getAllPackages();
    }

    @Override
    public PackageDetailsResponse getPackageDetailsStructured(String packageId) {
        return reabonnementRepository.getPackageDetailsStructured(packageId);
    }

    @Override
    public void addTransaction(TransactionDto transactionDto) {
        reabonnementRepository.addTransaction(transactionDto);
    }

    @Override
    public List<TransactionDto> getAllTransactions() {
        return reabonnementRepository.getAllTransactions();
    }

    // Méthode helper pour formater les dates
    private String formatDate(String date) {
        if (date == null) return null;

        // Remplacer - par /
        date = date.replace("-", "/");

        // Parser la date
        String[] parts = date.split("/");
        if (parts.length == 3) {
            try {
                int first = Integer.parseInt(parts[0]);
                int second = Integer.parseInt(parts[1]);
                int year = Integer.parseInt(parts[2]);

                // Déterminer le format
                if (first > 12) {
                    // C'est DD/MM/YYYY
                    return String.format("%02d/%02d/%04d", first, second, year);
                } else if (second > 12) {
                    // C'est MM/DD/YYYY, on inverse
                    return String.format("%02d/%02d/%04d", second, first, year);
                } else {
                    // Ambigu - on suppose MM/DD/YYYY en prod et on inverse
                    return String.format("%02d/%02d/%04d", second, first, year);
                }
            } catch (NumberFormatException e) {
                return date;
            }
        }

        return date;
    }

    // Méthode helper pour extraire une valeur d'un JSON simple
    private String extractJsonValue(String json, String key) {
        try {
            String searchKey = "\"" + key + "\":\"";
            int startIndex = json.indexOf(searchKey);
            if (startIndex != -1) {
                startIndex += searchKey.length();
                int endIndex = json.indexOf("\"", startIndex);
                if (endIndex != -1) {
                    return json.substring(startIndex, endIndex);
                }
            }
        } catch (Exception e) {
            log.error("Erreur extraction JSON key '{}': {}", key, e.getMessage());
        }
        return null;
    }

    private String safeExtractText(WebElement parent, String selector) {
        try {
            return parent.findElement(By.cssSelector(selector)).getText();
        } catch (Exception e) {
            return "";
        }
    }

    private boolean isAccountTemporarilyBlocked(String username) {
        LocalDateTime blockedUntil = blocked2FAAccounts.get(username);
        if (blockedUntil != null) {
            if (LocalDateTime.now().isBefore(blockedUntil)) {
                return true;
            } else {
                // Le blocage a expiré, retirer du cache
                blocked2FAAccounts.remove(username);
            }
        }
        return false;
    }

    private AccessDto getNextAvailableAccount(Set<String> failedAccounts) {
        try {
            // Récupérer tous les comptes actifs
            List<AccessDto> activeAccounts = reabonnementRepository.findAllActiveAccess();

            // Filtrer les comptes qui n'ont pas encore échoué
            List<AccessDto> availableAccounts = activeAccounts.stream()
                    .filter(account -> !failedAccounts.contains(account.getUsername()))
                    .sorted((a, b) -> {
                        // Trier par dernière utilisation (le moins récent en premier)
                        if (a.getLastUsedAt() == null) return -1;
                        if (b.getLastUsedAt() == null) return 1;
                        return a.getLastUsedAt().compareTo(b.getLastUsedAt());
                    })
                    .collect(Collectors.toList());

            if (availableAccounts.isEmpty()) {
                return null;
            }

            // Retourner le compte le moins récemment utilisé
            return availableAccounts.get(0);

        } catch (Exception e) {
            log.error("Erreur lors de la récupération des comptes: {}", e.getMessage());
            return null;
        }
    }

    private void blockAccountTemporarily(String username) {
        blocked2FAAccounts.put(username, LocalDateTime.now().plusMinutes(BLOCK_DURATION_MINUTES));
        log.info("🔒 Compte {} bloqué temporairement pour {} minutes", username, BLOCK_DURATION_MINUTES);
    }

    // Nouvelle méthode pour envoyer un SMS d'alerte 2FA
    private void send2FAAlertSMS(String accountUsername) {
        smsExecutor.execute(() -> {
            try {
                TokenResponse token = orangeSmsService.getOAuthToken();
                if (!isTokenValid(token)) {
                    log.error("❌ Token invalide pour envoi SMS alerte 2FA");
                    return;
                }

                String message = String.format("🚨 URGENT: Veuillez activer la session du compte %s via Google Authenticator",
                        accountUsername);

                // Envoyer aux deux numéros
                String[] adminNumbers = {"+224621091895"};

                for (String number : adminNumbers) {
                    try {
                        orangeSmsService.sendSms(token.getToken(), number, senderName, message);
                        log.info("📱 SMS d'alerte 2FA envoyé à {}", number);
                        Thread.sleep(500); // Petite pause entre les envois
                    } catch (Exception e) {
                        log.error("Erreur envoi SMS à {}: {}", number, e.getMessage());
                    }
                }

            } catch (Exception e) {
                log.error("Erreur lors de l'envoi des SMS d'alerte 2FA: {}", e.getMessage());
            }
        });
    }

    // Nouvelle méthode pour mettre à jour la dernière utilisation d'un compte
    private void updateAccountLastUsed(AccessDto account) {
        try {
            String updateSql = "UPDATE accesscanal SET last_used_at = CURRENT_TIMESTAMP WHERE id = ?";
            jdbcTemplate.update(updateSql, account.getId());
            log.debug("✅ Dernière utilisation mise à jour pour le compte {}", account.getUsername());
        } catch (Exception e) {
            log.error("Erreur mise à jour last_used_at: {}", e.getMessage());
            // Ne pas faire échouer le processus principal
        }
    }
    @Scheduled(fixedDelay = 600000) // Toutes les 10 minutes
    private void cleanupBlockedAccountsCache() {
        int beforeSize = blocked2FAAccounts.size();
        blocked2FAAccounts.entrySet().removeIf(entry ->
                LocalDateTime.now().isAfter(entry.getValue())
        );
        int removed = beforeSize - blocked2FAAccounts.size();
        if (removed > 0) {
            log.info("🧹 Nettoyage cache 2FA: {} comptes débloqués", removed);
        }
    }

    @Override
    public Map<String, Object> getAccountsStatus() {
        Map<String, Object> status = new HashMap<>();

        try {
            List<AccessDto> allAccounts = reabonnementRepository.findAllActiveAccess();

            status.put("total_accounts", allAccounts.size());
            status.put("blocked_by_2fa", blocked2FAAccounts.size());
            status.put("in_use", accountsInUse.size());
            status.put("available_now", allAccounts.size() - blocked2FAAccounts.size() - accountsInUse.size());

            // Détails des comptes bloqués par 2FA
            List<Map<String, Object>> blockedDetails = new ArrayList<>();
            blocked2FAAccounts.forEach((username, blockedUntil) -> {
                Map<String, Object> detail = new HashMap<>();
                detail.put("username", username);
                detail.put("blocked_until", blockedUntil.toString());
                detail.put("minutes_remaining",
                        Duration.between(LocalDateTime.now(), blockedUntil).toMinutes());
                detail.put("reason", "2FA");
                blockedDetails.add(detail);
            });

            // Détails des comptes en cours d'utilisation
            List<Map<String, Object>> inUseDetails = new ArrayList<>();
            accountsInUse.forEach((username, lockedUntil) -> {
                Map<String, Object> detail = new HashMap<>();
                detail.put("username", username);
                detail.put("locked_until", lockedUntil.toString());
                detail.put("minutes_remaining",
                        Duration.between(LocalDateTime.now(), lockedUntil).toMinutes());
                detail.put("reason", "IN_USE");
                inUseDetails.add(detail);
            });

            status.put("blocked_accounts_details", blockedDetails);
            status.put("in_use_accounts_details", inUseDetails);

        } catch (Exception e) {
            log.error("Erreur récupération statut comptes: {}", e.getMessage());
        }

        return status;
    }

}