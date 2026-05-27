package com.TCC.FlyGuide.services;

import com.TCC.FlyGuide.entities.Imagem;
import com.TCC.FlyGuide.repositories.ImagemRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ImagemSeeder implements ApplicationRunner {

    @Autowired
    private ImagemRepository imagemRepository;

    private static final List<Imagem> IMAGENS_PADRAO = List.of(
        new Imagem(null, "cidade",      "Cidade",      "https://images.unsplash.com/photo-1477959858617-67f85cf4f1df?auto=format&fit=crop&w=800&q=80", "🏙️"),
        new Imagem(null, "praia",       "Praia",       "https://images.unsplash.com/photo-1507525428034-b723cf961d3e?auto=format&fit=crop&w=800&q=80", "🏖️"),
        new Imagem(null, "natureza",    "Natureza",    "https://images.unsplash.com/photo-1448375240586-882707db888b?auto=format&fit=crop&w=800&q=80", "🌿"),
        new Imagem(null, "aventura",    "Aventura",    "https://images.unsplash.com/photo-1501854140801-50d01698950b?auto=format&fit=crop&w=800&q=80", "🧗"),
        new Imagem(null, "cultural",    "Cultural",    "https://images.unsplash.com/photo-1539037116277-4db20889f2d4?auto=format&fit=crop&w=800&q=80", "🎭"),
        new Imagem(null, "gastronomia", "Gastronomia", "https://images.unsplash.com/photo-1414235077428-338989a2e8c0?auto=format&fit=crop&w=800&q=80", "🍽️"),
        new Imagem(null, "mochilao",    "Mochilão",    "https://images.unsplash.com/photo-1501554728187-ce583db33af7?auto=format&fit=crop&w=800&q=80", "🎒"),
        new Imagem(null, "luxo",        "Luxo",        "https://images.unsplash.com/photo-1566073771259-6a8506099945?auto=format&fit=crop&w=800&q=80", "💎"),
        new Imagem(null, "familia",     "Família",     "https://images.unsplash.com/photo-1476514525535-07fb3b4ae5f1?auto=format&fit=crop&w=800&q=80", "👨‍👩‍👧"),
        new Imagem(null, "montanha",    "Montanha",    "https://images.unsplash.com/photo-1464822759023-fed622ff2c3b?auto=format&fit=crop&w=800&q=80", "⛰️"),
        new Imagem(null, "neve",        "Neve / Frio", "https://images.unsplash.com/photo-1551582045-6ec9c11d8697?auto=format&fit=crop&w=800&q=80", "❄️")
    );

    @Override
    public void run(ApplicationArguments args) {
        for (Imagem padrao : IMAGENS_PADRAO) {
            imagemRepository.findByChave(padrao.getChave()).ifPresentOrElse(
                existing -> {
                    existing.setUrl(padrao.getUrl());
                    existing.setNome(padrao.getNome());
                    existing.setEmoji(padrao.getEmoji());
                    imagemRepository.save(existing);
                },
                () -> imagemRepository.save(padrao)
            );
        }
    }
}
