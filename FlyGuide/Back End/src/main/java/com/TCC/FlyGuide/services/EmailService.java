package com.TCC.FlyGuide.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class EmailService {

    private static final Logger logger = LoggerFactory.getLogger(EmailService.class);

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

    private void validarConfiguracao() {
        if (mailUsername == null || mailUsername.isBlank() || mailPassword == null || mailPassword.isBlank()) {
            throw new IllegalStateException("SMTP nao configurado: defina MAIL_USERNAME e MAIL_PASSWORD no ambiente");
        }
    }

    public void enviarOtpResetSenha(String destinatario, String codigo) {
        validarConfiguracao();
        logger.info("🔥 [EMAIL] Iniciando envio OTP reset senha | De: {} | Para: {} | Host: {}:{} | User: {}",
                remetente, destinatario, mailHost, mailPort, mailUsername);
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(remetente);
            message.setTo(destinatario);
            message.setSubject("FlyGuide - Recuperação de Senha");
            message.setText(
                    "Olá!\n\n" +
                    "Recebemos uma solicitação para redefinir a senha da sua conta FlyGuide.\n\n" +
                    "Seu código de verificação é: " + codigo + "\n\n" +
                    "Este código expira em 10 minutos.\n\n" +
                    "Se você não solicitou a redefinição de senha, ignore este e-mail.\n\n" +
                    "Equipe FlyGuide"
            );
            mailSender.send(message);
            logger.info("✅ [EMAIL] OTP reset senha enviado com sucesso para: {}", destinatario);
        } catch (Exception e) {
            logger.error("❌ [EMAIL] Falha ao enviar OTP reset senha para: {} | Erro: {} | Causa: {}",
                    destinatario, e.getMessage(), e.getCause() != null ? e.getCause().getMessage() : "sem causa");
            throw e;
        }
    }

    public void enviarOtpLogin(String destinatario, String codigo) {
        validarConfiguracao();
        logger.info("🔥 [EMAIL] Iniciando envio OTP login | De: {} | Para: {} | Host: {}:{} | User: {}",
                remetente, destinatario, mailHost, mailPort, mailUsername);
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(remetente);
            message.setTo(destinatario);
            message.setSubject("FlyGuide - Código de Acesso");
            message.setText(
                    "Olá!\n\n" +
                    "Seu código de acesso ao FlyGuide é: " + codigo + "\n\n" +
                    "Este código expira em 10 minutos.\n\n" +
                    "Se você não tentou fazer login, ignore este e-mail.\n\n" +
                    "Equipe FlyGuide"
            );
            mailSender.send(message);
            logger.info("✅ [EMAIL] OTP login enviado com sucesso para: {}", destinatario);
        } catch (Exception e) {
            logger.error("❌ [EMAIL] Falha ao enviar OTP login para: {} | Erro: {} | Causa: {}",
                    destinatario, e.getMessage(), e.getCause() != null ? e.getCause().getMessage() : "sem causa");
            throw e;
        }
    }
}
