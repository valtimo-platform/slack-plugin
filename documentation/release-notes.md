# Release notes

Overzicht van wijzigingen per versie van de Slack-plugin.

## 6.1.0

Nieuwe actie `receive-message`: een Slack-kanaal uitlezen en per bericht een dossier starten,
of een dossier vervolgen met het antwoord in de thread. Een planner leest alleen de kanalen
waar een proceskoppeling naar verwijst, slaat de geschiedenis van een kanaal over en houdt
per kanaal bij hoe ver het gelezen is. `post-message` legt daarnaast vast in welke thread het
bericht is geplaatst, zodat een dossier op de antwoorden kan wachten, en kan met de nieuwe
eigenschap *Thread* zelf in een bestaande thread antwoorden.

## 6.0.1

Valtimo bijgewerkt naar versie 13.41.0.

## 6.0.0
Ondergebracht in een eigen repository met voorbeeldapplicatie, aparte documentatie en een PR-checks workflow. Broncode gesynchroniseerd met de monorepo en ktlint-issues opgelost.

## 5.0.0
Eerste publieke release: berichten en bijlagen versturen naar een Slack-kanaal.
