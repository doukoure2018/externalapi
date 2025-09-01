package crg.api.external.service.impl;

import crg.api.external.dto.AccessDto;
import crg.api.external.dto.TokenResponse;
import crg.api.external.dto.reabo.ReabonnementRequest;
import crg.api.external.enumeration.ValidationStatus;
import crg.api.external.repository.AccessRepository;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.*;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.support.ui.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
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

    @Value("${selenium.remote.url:}")
    private String seleniumRemoteUrl;
    @Value("${chrome.remote.enabled:false}")
    private boolean chromeRemoteEnabled;

    @Value("${sms.sender.name}")
    private String senderName;

    @Value("${slack.credential}")
    private String slackCredential;

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
        driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(20));
        driver.manage().timeouts().scriptTimeout(Duration.ofSeconds(10));
    }

    // SMS de début de processus


    // SMS de succès avec détails





    @Override
    public String effectuerReabonnement(ReabonnementRequest req) {
       return "this is the service for reabo";
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

            // 7. Valider
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

            validButton.click();
            log.info("✅ Clicked validation button");

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
                Thread.sleep(1000);

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
        AccessDto accessDto = accessRepository.findActiveAccess();
        log.info("🔐 Login rapide...");
        driver.get("https://cgaweb-afrique.canal-plus.com/mypos/");

        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(15));
        WebElement loginInput = wait.until(ExpectedConditions.presenceOfElementLocated(LOGIN_INPUT));
        WebElement passwordInput = wait.until(ExpectedConditions.presenceOfElementLocated(PASSWORD_INPUT));

        loginInput.clear();
        //loginInput.sendKeys(login);
        loginInput.sendKeys(accessDto.getUsername());
        passwordInput.clear();
        //passwordInput.sendKeys(password);
        passwordInput.sendKeys(accessDto.getPassword());

        passwordInput.sendKeys(Keys.RETURN);

        try {
            wait.withTimeout(Duration.ofSeconds(15)).until(ExpectedConditions.or(
                    ExpectedConditions.urlContains("dashboard"),
                    ExpectedConditions.urlContains("search-subscriber"),
                    ExpectedConditions.presenceOfElementLocated(SUBSCRIBER_INPUT)
            ));
            log.info("✅ Login successful");
        } catch (TimeoutException e) {
            throw new RuntimeException("Login timeout after 15 seconds");
        }
    }

    private boolean performRobustSearch(WebDriver driver, JavascriptExecutor js,
                                        WebDriverWait wait, String numAbonne) {
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

    private boolean performValidationWithConfirmation(WebDriver driver, JavascriptExecutor js, WebDriverWait wait) {
        try {
            log.info("📋 Début de la validation avec attente de confirmation...");

            // Capturer l'état du formulaire avant validation
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
                    // Indiquer si SANS_OPTION (value vide ou "Choisir...")
                    if (option.value === '' || option.options[option.selectedIndex].text.includes('Choisir')) {
                        form.isSansOption = true;
                    }
                }
                return JSON.stringify(form);
            """);

                log.info("📸 État du formulaire avant validation: {}", formState);

                // Parser pour vérifier si SANS_OPTION
                if (formState != null && formState.contains("\"isSansOption\":true")) {
                    log.info("✅ Validation avec SANS_OPTION confirmée");
                }

            } catch (Exception e) {
                log.debug("Impossible de capturer l'état du formulaire: {}", e.getMessage());
            }

            WebElement validationButton = findValidationButton(driver, wait);
            if (validationButton == null) {
                log.warn("⚠️ Aucun bouton de validation trouvé");
                return false;
            }

            js.executeScript("arguments[0].scrollIntoView({behavior: 'smooth', block: 'center'});", validationButton);
            Thread.sleep(500);

            wait.until(ExpectedConditions.elementToBeClickable(validationButton));

            try {
                validationButton.click();
                log.info("✅ Bouton de validation cliqué");
            } catch (Exception e) {
                js.executeScript("arguments[0].click();", validationButton);
                log.info("✅ Bouton de validation cliqué (JavaScript)");
            }

            log.info("⏳ Attente du résultat...");
            Thread.sleep(3000);

            ValidationResult result = waitForValidationResult(driver, wait);

            switch (result.getStatus()) {
                case SUCCESS:
                    log.info("🎉 Validation réussie : {}", result.getMessage());
                    try {
                        Thread.sleep(1000);
                        clickContinueButton(driver);
                    } catch (Exception e) {
                        log.debug("Bouton Continuer non cliqué: {}", e.getMessage());
                    }
                    return true;

                case ERROR:
                    // Vérifier si c'est une erreur liée à l'option
                    if (result.getMessage() != null &&
                            result.getMessage().toLowerCase().contains("payment mean")) {
                        log.warn("⚠️ Erreur 'payment mean' détectée - Vérification si c'est un faux positif...");

                        Thread.sleep(3000);

                        // Vérifier si malgré l'erreur, le réabonnement est passé
                        String currentUrl = driver.getCurrentUrl();
                        if (!currentUrl.contains("search-subscriber") ||
                                currentUrl.contains("success") ||
                                currentUrl.contains("confirmation") ||
                                currentUrl.contains("reports")) {
                            log.info("✅ Succès confirmé malgré l'erreur payment mean");
                            return true;
                        }
                    }

                    log.error("❌ Erreur de validation : {}", result.getMessage());
                    throw new RuntimeException(result.getMessage());

                case TIMEOUT:
                    log.warn("⏱️ Timeout, vérification finale...");

                    // Capturer l'état de la page pour debug
                    try {
                        String pageInfo = (String) js.executeScript("""
                        return JSON.stringify({
                            url: window.location.href,
                            title: document.title,
                            bodyText: document.body.innerText.substring(0, 500),
                            hasErrors: !!document.querySelector('#sas-alert'),
                            hasInvoice: !!document.querySelector('[class*="invoice"]'),
                            visibleButtons: Array.from(document.querySelectorAll('button:not([style*="none"])')).map(b => b.textContent).slice(0, 5)
                        });
                    """);
                        log.info("📸 État de la page au timeout: {}", pageInfo);
                    } catch (Exception ex) {
                        log.debug("Impossible de capturer l'état de la page");
                    }

                    Thread.sleep(3000);

                    // Vérifier d'abord s'il y a une erreur
                    String errorCheck = checkForErrors(driver, wait);
                    if (errorCheck != null) {
                        // Cas spécial : erreur "payment mean" avec SANS_OPTION
                        if (errorCheck.contains("payment mean") || errorCheck.contains("OPTION_NON_SELECTIONNEE")) {
                            log.warn("⚠️ Erreur option détectée, vérification du succès réel...");

                            Thread.sleep(2000);
                            String currentUrl = driver.getCurrentUrl();

                            if (!currentUrl.contains("search-subscriber") ||
                                    currentUrl.contains("success") ||
                                    currentUrl.contains("reports") ||
                                    isSuccessMessageDisplayed(driver)) {
                                log.info("✅ Succès confirmé malgré l'erreur option");
                                return true;
                            }
                        }

                        if (!errorCheck.equals("ERREUR_INCONNUE")) {
                            log.error("❌ Erreur détectée après timeout : {}", errorCheck);
                            throw new RuntimeException(errorCheck);
                        }
                    }

                    // Si pas d'erreur, vérifier les signes de succès
                    if (isSuccessMessageDisplayed(driver)) {
                        log.info("✅ Message de succès trouvé après timeout");
                        return true;
                    }

                    String currentUrl = driver.getCurrentUrl();
                    if (!currentUrl.contains("search-subscriber") ||
                            currentUrl.contains("reports") ||
                            currentUrl.contains("facture") ||
                            currentUrl.contains("invoice") ||
                            currentUrl.contains("success") ||
                            currentUrl.contains("confirmation")) {
                        log.info("✅ URL suggère un succès: {}", currentUrl);
                        return true;
                    }

                    // En cas de timeout sans erreur, considérer comme succès
                    log.info("✅ Timeout sans erreur = succès présumé");
                    return true;

                default:
                    return false;
            }

        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            log.error("Erreur lors de la validation : {}", e.getMessage());
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
            // Méthode 1: Chercher le div avec la classe operation-achieved-div
            var successDiv = document.querySelector('.operation-achieved-div');
            if (successDiv) {
                var text = successDiv.textContent || successDiv.innerText || '';
                text = text.replace(/\\s+/g, ' ').trim();
                
                if (text.includes('réabonnement a été fait avec succès') || 
                    text.includes('Le réabonnement a été fait avec succès') ||
                    text.includes('abonnement a été fait avec succès') ||
                    text.includes('succès') || 
                    text.includes('réussi')) {
                    console.log('Success found in operation-achieved-div:', text);
                    return text;
                }
            }
            
            // Méthode 2: Chercher un message de succès plus large
            var allDivs = document.querySelectorAll('div, span, p');
            for (var i = 0; i < allDivs.length; i++) {
                var text = (allDivs[i].textContent || '').toLowerCase();
                if ((text.includes('succès') || text.includes('réussi') || text.includes('successful')) &&
                    (text.includes('réabonnement') || text.includes('abonnement') || text.includes('renewal'))) {
                    console.log('Success found by broad search:', text);
                    return allDivs[i].textContent;
                }
            }
            
            // Méthode 3: Vérifier si on est sur une page de confirmation/facture
            var url = window.location.href;
            if (url.includes('success') || url.includes('confirmation') || 
                url.includes('invoice') || url.includes('facture')) {
                return 'SUCCESS_BY_URL';
            }
            
            // Méthode 4: Chercher des éléments spécifiques de succès
            var successIndicators = [
                document.querySelector('.success-message'),
                document.querySelector('.alert-success'),
                document.querySelector('[class*="success"]'),
                document.querySelector('[class*="achieved"]')
            ];
            
            for (var elem of successIndicators) {
                if (elem && elem.textContent) {
                    console.log('Success indicator found:', elem.className);
                    return elem.textContent;
                }
            }
            
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

    private String checkForErrors(WebDriver driver, WebDriverWait wait) {
        try {
            Thread.sleep(1000);

            List<WebElement> errorAlerts = driver.findElements(ERROR_ALERT);
            if (!errorAlerts.isEmpty() && errorAlerts.get(0).isDisplayed()) {
                WebElement errorDiv = errorAlerts.get(0);
                WebElement errorMsg = errorDiv.findElement(ERROR_MESSAGE);
                String errorText = errorMsg.getText();

                log.error("🚨 Erreur détectée: {}", errorText);

                // NOUVELLE GESTION : Erreur "payment mean"
                if (errorText.toLowerCase().contains("please select payment mean") ||
                        errorText.toLowerCase().contains("payment mean") ||
                        errorText.toLowerCase().contains("moyen de paiement")) {

                    log.error("❌ ERREUR PAYMENT MEAN - Option non sélectionnée correctement");

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
                    return "OPTION_NON_SELECTIONNEE";
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
    public Optional<Map<String, Object>> rechercherInfosAbonne(String numAbonne) {
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
}
