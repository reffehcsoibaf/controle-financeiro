# Histórico de versões

## 0.12.3
- Nota fiscal comprida, que não cabe numa foto: depois da primeira leitura, os botões "Fotografar
  mais itens" e "Escolher mais itens de um arquivo" leem outra parte da nota e SOMAM os itens aos que
  já estão no formulário, sem apagar nada. Fotografe cada parte sem repetir linhas.
- O aviso falado diz quantos itens foram acrescentados e a soma, e compara com o total da nota quando
  a foto o traz (a parte de baixo da nota). Avisa também se algum item parece repetido entre as
  fotos, sem removê-lo. O que já estava preenchido não é trocado; só se completa o que estava vazio,
  e a foto do fim da nota pode trazer o pagamento dividido.

## 0.12.2
- Foto de nota fiscal comprida: o limite de redução passou a ser pela área da imagem (cerca de 12
  megapixels, o que uma câmera comum faz) e não por lado. Antes, uma nota alta e estreita era
  reduzida demais e a letra se perdia.
- Ao reduzir uma foto, a orientação gravada pela câmera é aplicada: uma foto tirada em pé não chega
  mais deitada à IA.

## 0.12.1
- Novo botão "Fotografar documento" no Novo lançamento: abre a câmera do celular, sem pedir
  permissão de câmera, e lê a foto tirada. A foto fica só no armazenamento temporário do app e é
  apagada na próxima.

## 0.12.0
- Ler documento com IA, no Novo lançamento, como no programa Windows (mesmo servidor de leitura):
  - "Escolher documento": foto, PDF, TXT ou XML do celular. Fotos grandes são reduzidas antes de
    enviar, para subir rápido na rede móvel.
  - "Ler o texto copiado": lê o texto que estiver na área de transferência (por exemplo a mensagem
    de um comprovante).
  - Compartilhar: em outro app (WhatsApp, e-mail, galeria, arquivos), compartilhar uma imagem, PDF,
    texto ou XML com o Controle Financeiro abre o Novo lançamento e já lê.
- O que a IA leu entra no formulário e o aviso fala o que foi preenchido. Tudo pode ser corrigido
  antes de salvar. Banco, estabelecimento, forma, categoria, credor e devedor usam o texto das suas
  listas quando há equivalente (sem acento nem maiúscula; "Next Bradesco" vale "Next"). Dois ou mais
  itens viram itens, e dois ou mais pagamentos viram pagamento dividido.
- Texto que a IA leu e que não está nas suas listas entra como texto novo e o aviso diz quais são.
  Novo botão "Cadastrar textos novos nas listas" guarda nas listas o que você digitou de novo.
- As correções que você faz do que a IA sugeriu são guardadas ao salvar e servem de exemplo nas
  próximas leituras, também para o Windows e o site.
- Não existe ainda: guardar o documento junto do lançamento (aba Documentos) e ler planilha XLSX.

## 0.11.0
- Filtrar e ordenar ganhou a seção "Banco, forma e outros": banco ou cartão, forma de pagamento,
  estabelecimento, categoria, credor e devedor. Cada campo aceita texto livre e sugere as opções das
  suas listas, como no formulário. Filtra pelo texto exato (sem diferenciar acento nem maiúscula).
  Banco e forma também casam com as partes de um pagamento dividido, e a categoria com a dos itens,
  como no Windows.
- Os botões agora se chamam "Aplicar filtros" e "Limpar filtros" e valem para tudo da tela. O aviso
  "Com filtros. Mostrando X de Y" passa a contar também esses filtros.
- Retirado o registro técnico que o botão Aplicar gravava no log do celular.

## 0.10.0
- Excluir lançamento: nos Detalhes há o botão "Excluir lançamento", que abre uma tela própria de
  confirmação. O foco começa em "Cancelar". A tela diz o que será apagado (o lançamento, e quantos
  itens e pagamentos vão junto) e avisa que não dá para desfazer.
- Se o lançamento está vinculado a outro (o par do pagamento de fatura), a tela mostra o outro e
  oferece "Excluir os dois" ou "Excluir só este", como no programa Windows.
- Só exclui se o lançamento continua como o app o leu. Se mudou em outro lugar, nada é apagado: a
  lista é atualizada e o app avisa. Documentos ligados ao lançamento só perdem a ligação.
- Removida a caixa "Este item é um desconto": o desconto é o sinal de menos no valor do item.

## 0.9.0
- Editar lançamento: nos Detalhes há o botão "Editar lançamento". Abre o mesmo formulário do Novo
  lançamento, já preenchido com o que está gravado (tipo, valor, datas, banco, forma, itens,
  pagamento dividido, Pago...). Salvar grava pela mesma função do servidor que o Windows usa.
- Se o lançamento foi alterado em outro lugar (Windows ou site) desde que o app o leu, nada é
  gravado: o formulário fecha, a lista é atualizada e o app avisa para refazer a alteração.
- Ao salvar, o app volta aos Detalhes do lançamento e fala o resultado.
- O valor do item aceita o sinal de menos para desconto; a caixa "Este item é um desconto" foi retirada, por ser mais um elemento a percorrer com o TalkBack.
- Itens antigos guardam a data do lançamento de onde vieram; essa data é preservada ao editar.
- Na edição não há a seção Parcelar (editar altera só este lançamento). O rótulo de parcela, como
  "2/3", é mantido. A categoria Cartão de crédito (pagamento de fatura) continua sem suporte.

## 0.8.0
- Novo lançamento ganhou três seções depois dos campos, cada uma com o seu título, como as abas do
  programa Windows: Itens do lançamento, Pagamento dividido e Parcelar. Tudo na própria tela, sem
  janela por cima.
- Itens: a seção começa recolhida, com um botão "Abrir os itens". Cada item tem valor, categoria e
  descrição, e o sinal de menos no valor do item faz dele um desconto (sem caixa extra para marcar).
  Cada item da lista tem os botões "Editar item N" e "Remover item N". Com itens, o valor do
  lançamento é a soma deles, e categoria e descrição passam a ser dos itens. Cada mudança é falada
  com o total.
- Pagamento dividido: a caixa "Dividir pagamento" esconde banco e forma e mostra as partes (banco
  ou cartão, forma e valor), cada uma com Editar e Remover. A soma das partes precisa bater com o
  valor do lançamento, como no Windows.
- Parcelar: a seção começa recolhida, com um resumo sempre visível ("Sem parcelas" ou o total e a
  primeira e a última parcela). Aberta, pede o número de parcelas (de 2 a 360) e onde fica a
  diferença de centavos (última ou primeira parcela), e lista cada parcela com vencimento e valor.
  Salvar cria um lançamento por parcela, com vencimento mês a mês (o dia é mantido, ou o último dia
  do mês) e o rótulo "1/3". Itens e pagamentos são divididos em valores proporcionais, em centavos
  exatos. As parcelas começam pendentes. Cada parcela tem o seu código de envio: se a conexão cair
  no meio, tentar de novo não repete as que já foram criadas.
- As contas (parcelas, rateio e meses) são as do Windows (`regras.py`); testes conferem os mesmos
  números.
- Ainda não existe: importar lançamentos antigos como itens, ler documento por IA e pagamento de
  fatura (categoria Cartão de crédito).

## 0.7.0
- Novo lançamento: botão "Novo lançamento" na faixa fixa da aba Lançamentos abre um formulário em
  tela própria, com Salvar e Cancelar fixos embaixo e Alt+S para salvar. Campos: tipo (saída ou
  entrada), valor, data de origem (hoje por padrão), vencimento, banco ou cartão, forma de pagamento,
  estabelecimento, categoria, credor, devedor, descrição e Pago.
- Os campos de lista aceitam texto livre e sugerem as opções cadastradas conforme se digita; "Ver
  opções" abre a lista inteira na própria tela, sem janela sobreposta.
- Grava pela mesma função do servidor que o programa Windows usa. Um código de envio evita criar
  o lançamento duas vezes se a resposta se perder e a pessoa tentar de novo.
- As conferências são as do Windows (data de origem obrigatória, valor em números, vencimento
  válido ou vazio). A categoria Cartão de crédito (pagamento de fatura) ainda é recusada, porque
  exige o cartão a quitar; fica para uma próxima versão, junto com itens, pagamento dividido e
  parcelar.

## 0.6.0
- Mais > Configurações > Importar configurações do Windows: lê o preferencias.json do programa do
  Windows (escolhido no seletor de arquivos do Android) e aplica os campos, a ordem e as opções de
  informar quantidade e saldo ao filtrar. Ordem, tipo e situação da lista não são importados, por
  serem o filtro do momento. Se o arquivo não servir, nada muda e o app avisa.
- ferramentasnviar_configuracoes_para_celular.bat copia o arquivo do Windows para a pasta
  Download do celular.

## 0.5.1
- Configurações: os textos não falam mais de leitor de tela. A explicação agora só diz que a ordem
  é a dos elementos exibidos, e as opções viraram "Informar a quantidade de lançamentos" e
  "Informar o saldo em valores". Teste novo impede esse tipo de texto de voltar.

## 0.5.0
- Mais > Configurações (tela própria): escolha quais informações de cada lançamento aparecem e em
  que ordem. Cada campo tem uma caixa de seleção e os botões "Mover X para cima" e "Mover X para
  baixo" (sem arrastar, para funcionar bem com o leitor de tela). A ordem vale para a frase falada,
  para a lista e para os Detalhes. Itens, credor e devedor começam desligados.
- Ao filtrar, escolha se o aviso fala a quantidade de lançamentos e o saldo, como no Windows.
- Botão "Restaurar o padrão". As escolhas ficam guardadas no celular.
- A linha da lista mostra categoria, que antes só era falada.

## 0.4.0
- Tocar numa linha da lista abre a tela "Detalhes do lançamento": todos os campos preenchidos, um por
  linha, e os itens e pagamentos. A linha é anunciada como botão, e o toque duplo diz "abrir os
  detalhes".
- Nos detalhes, o botão "Marcar como pago" ou "Marcar como pendente". Grava no servidor e só então
  muda na tela. Só grava se o lançamento não mudou em outro lugar desde que foi lido (como no
  Windows); se mudou, nada é alterado, a lista é atualizada e o app avisa.
- As chamadas à rede passam a usar OkHttp, porque o Android não faz o tipo de chamada (PATCH) que a
  gravação exige.

## 0.3.2
- Na aba Lançamentos, os botões Atualizar e Filtrar e ordenar ficam numa faixa fixa acima da
  lista. Antes rolavam junto, e com a lista longa era preciso voltar ao início para alcançá-los.
- Teste novo: com 200 lançamentos e a lista rolada até o fim, os botões continuam na tela.

## 0.3.1
- O aviso (erros, "Mostrando X de Y", "Carregando") passa a ficar fixo acima da lista, em todas as
  telas. Antes ele rolava junto, e quando a pessoa descia até o botão Aplicar o leitor de tela não
  conseguia falá-lo.
- Registro técnico no botão Aplicar da tela Filtrar e ordenar (só tamanhos, nunca o conteúdo), para
  diagnosticar por que a tela não fechava no celular.
- Testes de tela com Robolectric: simulam toques e conferem o que o leitor de tela encontraria.

## 0.3.0
- Nova tela "Filtrar e ordenar" (botão na aba Lançamentos): Ordem, Tipo e Situação em botões de
  opção, mais busca e quatro datas (origem e vencimento). Ordem, Tipo e Situação ficam guardados.
- O resumo e a lista passam a refletir os filtros. Quando algo está escondido, o app avisa:
  "Mostrando 12 de 340 lançamentos" ou "Com filtros. ...", como no Windows.
- Busca ignora acentos e maiúsculas e procura em descrição, estabelecimento, categoria, credor,
  devedor, banco, forma, valor, datas e itens.

## 0.2.0
- Duas áreas, escolhidas na barra de baixo: Lançamentos e Mais.
- Lançamentos: resumo (quantidade, entradas, saídas, saldo, pago e pendente) e a lista de
  todos os lançamentos, na ordem do programa Windows (data de origem, mais recentes primeiro).
- Cada lançamento diz "entrada" ou "saída" por palavra, e a descrição vem dos itens quando há.
- Mais: conta conectada, Sair da conta e versão.

## 0.1.0
- Esqueleto do app: login com Google (mesmo fluxo do Windows), conferência de
  acesso ao módulo e lista dos lançamentos mais recentes, somente leitura.
