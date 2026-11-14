# Controle Financeiro para Android

App nativo para Android, feito para uso com TalkBack e também por quem enxerga.
Mesmo Supabase do site. Ver `CLAUDE.md` para as regras, principalmente as de
acessibilidade.

## Preparar o computador

1. Rode `ferramentas\instalar_ferramentas.bat` (baixa o JDK 17 e as ferramentas do
   Android para `C:\android`; pede para você aceitar as licenças do Android).
2. Rode `ferramentas\verificar.bat` para conferir.
3. No celular: ative as opções do desenvolvedor, a depuração por USB e conecte o cabo.

A saída de cada .bat fica em `C:\logs`.

## Compilar e instalar no celular

Rode `ferramentas\instalar_no_celular.bat`. Ele roda os testes, compila, instala no
celular conectado e abre o app. A saída fica em `C:\logs\instalar_no_celular.txt`.

## Login com Google

O app usa o mesmo fluxo do programa Windows (Supabase + Google, PKCE), mas volta ao
app por um endereço próprio. Esta linha precisa estar na lista de Redirect URLs do
Supabase (Authentication > URL Configuration):

    br.com.fabioscheffer.controlefinanceiro://login
