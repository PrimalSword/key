# Kement Print Cam — Android 0.3 lab

Aplicativo-laboratório para usar uma **EKEN-Z26/Kement do próprio usuário** como câmera dedicada da impressora 3D.

## Instalação

1. Mantenha o aplicativo oficial **Kement (`com.eken.kement`) instalado** no mesmo celular.
2. Abra **Releases** neste repositório.
3. Baixe `KementPrintCam-0.3.0-lab.apk`.
4. Autorize temporariamente a instalação de apps da fonte usada para abrir o APK e instale.

O APK do Print Cam não redistribui as bibliotecas nativas proprietárias da Kement. Em runtime ele localiza a instalação oficial existente no aparelho, copia `libVCTP2P.so` e `libc++_shared.so` para o diretório privado do próprio Print Cam e carrega essas bibliotecas dali.

Por isso, para esta versão de laboratório, **não desinstale o Kement oficial**.

## O que mudou na 0.3

O teste da 0.2 mostrou uma condição importante: o Kement oficial recebia o toque enquanto o Print Cam podia permanecer conectado ao command server sem receber `preview-start`.

A 0.3 elimina essa dependência única:

1. autentica no backend oficial e faz `app-login` no command server;
2. inicia a sessão VCTP2P;
3. consulta `devices-state` a cada 1 segundo;
4. quando o botão físico acorda a Z26 e o SN aparece online, ativa a sessão de mídia e envia `stream-start`, mesmo que `preview-start` tenha se perdido;
5. tenta `connectToPeer` e faz retentativas controladas;
6. quando chegam pacotes de vídeo, desempacota H.264/H.265 e entrega ao `MediaCodec` do Android.

O fluxo normal por `preview-start` e `fast-streaming` continua existindo; o polling é um fallback adicional.

## Como testar

1. Abra **Kement Print Cam**.
2. Informe o e-mail e a senha da conta Kement.
3. Toque em **CONECTAR / AGUARDAR TOQUE**.
4. Espere aparecer `Pronto. Aperte UMA vez o botão da Kement.`
5. Aperte fisicamente a campainha uma única vez.
6. Observe o log.

Marcos relevantes:

- `app-login ACK`;
- `Fallback ativo: consultando devices-state a cada 1 s.`;
- `devices-state: campainha apareceu ONLINE`;
- `stream-start resposta | err=0`;
- `P2P conectado`;
- contador `P2P`/`vídeo` aumentando;
- `VÍDEO AO VIVO`.

## Build automático

Cada push para `main` executa `.github/workflows/android-apk.yml`.

O pipeline usa JDK 17, Android SDK 35 e Gradle 8.9, compila `:app:assembleDebug`, publica o APK como artifact do GitHub Actions e publica/atualiza a Release `v0.3.0-lab`.

O repositório contém somente o código do Print Cam; as `.so` da Kement não são versionadas nem incluídas no APK gerado.

## Build local

Abra a raiz do projeto no Android Studio.

Configuração:

- Android Gradle Plugin 8.7.3;
- Gradle 8.9;
- compileSdk 35;
- JDK 17;
- minSdk 26;
- ABI alvo: `arm64-v8a`.

## Segurança e escopo

- a senha não é persistida;
- somente o e-mail é lembrado localmente;
- REST usa HTTPS;
- o command channel usa TLS quando anunciado pelo backend;
- nenhum token de sessão ou credencial fica embutido no repositório;
- as bibliotecas nativas são obtidas exclusivamente da instalação oficial já presente no aparelho do proprietário;
- projeto destinado à interoperabilidade com dispositivo e conta do próprio usuário.
