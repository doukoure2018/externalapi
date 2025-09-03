package crg.api.external.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class SwaggerConfig {

    @Value("${application.title:External Application}")
    private String applicationTitle;

    @Value("${application.version:1.0.1}")
    private String applicationVersion;

    @Value("${server.port:8090}")
    private String serverPort;

    @Bean
    public OpenAPI customOpenAPI() {
        // Définir le schéma de sécurité JWT (Bearer Token)
        SecurityScheme securityScheme = new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT")
                .name("JWT Authentication")
                .description("Enter JWT Bearer token **_only_** (without 'Bearer' prefix)")
                .in(SecurityScheme.In.HEADER);

        // Créer le requirement de sécurité
        SecurityRequirement securityRequirement = new SecurityRequirement()
                .addList("Bearer Authentication");

        return new OpenAPI()
                // Informations de l'API
                .info(new Info()
                        .title(applicationTitle + " - API Documentation")
                        .version(applicationVersion)
                        .description("""
                                External API pour la gestion des réabonnements Canal+ et services associés.
                                
                                ## Authentification
                                Cette API utilise JWT (JSON Web Tokens) avec RSA pour l'authentification.
                                
                                ### Obtenir un token:
                                1. Utilisez `/api/auth/login` avec vos identifiants
                                2. Récupérez le `accessToken` dans la réponse
                                3. Cliquez sur le bouton 'Authorize' ci-dessous
                                4. Entrez le token (sans le préfixe 'Bearer')
                                
                                ### Utilisateur de test:
                                - Username: `admin`
                                - Password: `admin123`
                                """)
                        .contact(new Contact()
                                .name("External API Team")
                                .email("douklifsa93@gmail.com")
                                .url("https://github.com/doukoure2018/externalapi"))
                        .license(new License()
                                .name("Apache 2.0")
                                .url("https://www.apache.org/licenses/LICENSE-2.0")))

                // Serveurs
                .servers(List.of(
                        new Server()
                                .url("http://localhost:" + serverPort)
                                .description("Local Development Server"),
                        new Server()
                                .url("http://51.83.40.55:8090")
                                .description("OVH Production Server (Direct)"),
                        new Server()
                                .url("http://51.83.40.55")
                                .description("OVH Production Server (via Nginx)")
                ))

                // Composants de sécurité
                .components(new Components()
                        .addSecuritySchemes("Bearer Authentication", securityScheme))

                // Appliquer la sécurité globalement (sauf pour les endpoints publics)
                .addSecurityItem(securityRequirement);
    }
}