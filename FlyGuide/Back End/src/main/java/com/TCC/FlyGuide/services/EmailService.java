package com.TCC.FlyGuide.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Service
public class EmailService {

    private static final Logger logger = LoggerFactory.getLogger(EmailService.class);
    private static final URI BREVO_EMAIL_URI = URI.create("https://api.brevo.com/v3/smtp/email");

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private JavaMailSender mailSender;

    @Value("${spring.mail.from:flyguideltda@gmail.com}")
    private String remetente;

    @Value("${spring.mail.username:NAO_CONFIGURADO}")
    private String mailUsername;

    @Value("${spring.mail.password:}")
    private String mailPassword;

    @Value("${spring.mail.host:NAO_CONFIGURADO}")
    private String mailHost;

    @Value("${spring.mail.port:0}")
    private int mailPort;

    @Value("${brevo.api.key:}")
    private String brevoApiKey;

    @Value("${brevo.sender.email:${spring.mail.from:flyguideltda@gmail.com}}")
    private String brevoSenderEmail;

    @Value("${brevo.sender.name:FlyGuide}")
    private String brevoSenderName;

    private boolean usarBrevo() {
        return brevoApiKey != null && !brevoApiKey.isBlank();
    }

    private void validarSmtp() {
        if (mailUsername == null || mailUsername.isBlank() || mailPassword == null || mailPassword.isBlank()) {
            throw new IllegalStateException("SMTP nao configurado: defina MAIL_USERNAME e MAIL_PASSWORD no ambiente");
        }
    }

    private void enviarEmail(String destinatario, String assunto, String texto) {
        if (usarBrevo()) {
            enviarViaBrevo(destinatario, assunto, texto);
        } else {
            enviarViaSmtp(destinatario, assunto, texto);
        }
    }

    private void enviarViaBrevo(String destinatario, String assunto, String texto) {
        logger.info("[EMAIL] Enviando via Brevo API | De: {} | Para: {}", brevoSenderEmail, destinatario);

        try {
            String payload = objectMapper.writeValueAsString(Map.of(
                    "sender", Map.of(
                            "name", brevoSenderName,
                            "email", brevoSenderEmail
                    ),
                    "to", List.of(Map.of("email", destinatario)),
                    "subject", assunto,
                    "textContent", texto
            ));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(BREVO_EMAIL_URI)
                    .timeout(Duration.ofSeconds(20))
                    .header("accept", "application/json")
                    .header("api-key", brevoApiKey)
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                logger.error("[EMAIL] Brevo API retornou status {} para {} | Body: {}",
                        response.statusCode(), destinatario, response.body());
                throw new IllegalStateException("Falha ao enviar e-mail pela Brevo API");
            }

            logger.info("[EMAIL] E-mail enviado via Brevo API para: {}", destinatario);
        } catch (IOException e) {
            logger.error("[EMAIL] Falha de comunicacao com Brevo API para: {} | Erro: {}",
                    destinatario, e.getMessage());
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    private void enviarViaSmtp(String destinatario, String assunto, String texto) {
        validarSmtp();
        logger.info("[EMAIL] Enviando via SMTP | De: {} | Para: {} | Host: {}:{} | User: {}",
                remetente, destinatario, mailHost, mailPort, mailUsername);

        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(remetente);
            message.setTo(destinatario);
            message.setSubject(assunto);
            message.setText(texto);
            mailSender.send(message);
            logger.info("[EMAIL] E-mail enviado via SMTP para: {}", destinatario);
        } catch (Exception e) {
            logger.error("[EMAIL] Falha ao enviar via SMTP para: {} | Erro: {} | Causa: {}",
                    destinatario, e.getMessage(), e.getCause() != null ? e.getCause().getMessage() : "sem causa");
            throw e;
        }
    }

    public void enviarOtpResetSenha(String destinatario, String codigo) {
        enviarEmail(
                destinatario,
                "FlyGuide - Recuperacao de Senha",
                "Ola!\n\n" +
                        "Recebemos uma solicitacao para redefinir a senha da sua conta FlyGuide.\n\n" +
                        "Seu codigo de verificacao e: " + codigo + "\n\n" +
                        "Este codigo expira em 10 minutos.\n\n" +
                        "Se voce nao solicitou a redefinicao de senha, ignore este e-mail.\n\n" +
                        "Equipe FlyGuide"
        );
    }

    public void enviarOtpLogin(String destinatario, String codigo) {
        enviarEmail(
                destinatario,
                "FlyGuide - Codigo de Acesso",
                "Ola!\n\n" +
                        "Seu codigo de acesso ao FlyGuide e: " + codigo + "\n\n" +
                        "Este codigo expira em 10 minutos.\n\n" +
                        "Se voce nao tentou fazer login, ignore este e-mail.\n\n" +
                        "Equipe FlyGuide"
        );
    }
}
