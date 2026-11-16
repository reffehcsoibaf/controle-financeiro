package br.com.fabioscheffer.controlefinanceiro

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** O guia de uso do app: cada assunto é um título, para navegar por títulos no leitor de tela. */
object Guia {
    val assuntos: List<Pair<String, List<String>>> = listOf(
        "Primeiros passos" to listOf(
            "O Controle Financeiro para Android usa os mesmos dados do site e do programa do Windows. O que você faz aqui aparece lá, e o que você faz lá aparece aqui.",
            "Na primeira vez, escolha Entrar com Google. O login abre no navegador; ao terminar, o app abre sozinho. Depois disso, ele abre direto na sua conta.",
            "O app tem duas guias na parte de baixo: Lançamentos e Mais.",
        ),
        "Lançamentos" to listOf(
            "A lista mostra todos os lançamentos, com o resumo no começo: quantidade, entradas, saídas, saldo e pendente. O botão Atualizar busca os dados de novo.",
            "Tocar num lançamento abre os detalhes. Nos detalhes: Marcar como pago ou pendente, Editar, Copiar detalhes, Excluir, Ir ao lançamento vinculado e os documentos.",
            "No mesmo dia, os lançamentos ficam na ordem em que foram criados, seja qual for a ordem escolhida.",
        ),
        "Filtrar e ordenar" to listOf(
            "O botão Filtrar e ordenar abre a tela de filtros: ordem, tipo (entradas e saídas), situação (pagos e pendentes), busca por texto, datas de origem e de vencimento, e as listas de banco ou cartão, forma, estabelecimento, categoria, credor e devedor.",
            "Em uso, o resumo começa com Com filtros, para você saber que nem tudo está aparecendo. Limpar filtros mostra tudo de novo.",
            "Nos campos de data, digite só os números: dia, mês e ano, como 25102026.",
        ),
        "Novo lançamento" to listOf(
            "O botão Novo lançamento abre o formulário. Salvar e Cancelar ficam fixos embaixo.",
            "Digite o valor sem sinal: quem define entrada ou saída é o campo Tipo. Valores como 1.234,50 ou 12,5 são aceitos.",
            "Banco, forma, estabelecimento, categoria, credor e devedor sugerem as opções cadastradas enquanto você digita, e também aceitam um texto novo. Para cadastrar de vez os textos novos, use o botão Cadastrar textos novos nas listas.",
            "Ao salvar, o app fala o valor gravado. Se o lançamento não aparecer na lista, é porque os filtros atuais o escondem, e o app avisa.",
        ),
        "Itens, pagamento dividido e parcelas" to listOf(
            "Itens: use Adicionar item para cada produto. Com itens, o valor do lançamento passa a ser a soma deles. Desconto é um valor com sinal de menos.",
            "Pagamento dividido: para uma compra paga por mais de uma conta ou forma. A soma das partes precisa ser igual ao valor do lançamento.",
            "Parcelar, só em lançamento novo: informe o número de parcelas, de 2 a 360. Cada parcela vira um lançamento pendente, com etiqueta como 3/12 e vencimento avançando mês a mês.",
        ),
        "Pagamento de fatura" to listOf(
            "Ao pagar a fatura de um cartão, use a categoria Cartão de crédito e escolha o Cartão a quitar. Ao salvar, o app cria também uma entrada no cartão, para recuperar o limite, e vincula os dois lançamentos.",
            "Ao excluir um deles, o app pergunta se exclui os dois.",
        ),
        "Leitura por IA" to listOf(
            "Precisa de internet e de a função estar liberada na sua conta. No Novo lançamento: Escolher documento, Fotografar documento e Ler o texto copiado. Também dá para compartilhar um arquivo de outro app para este.",
            "A IA preenche o que encontra e o app fala o que foi preenchido. Com 2 ou mais produtos, preenche os itens. Sempre confira antes de salvar: nada é gravado até você salvar.",
            "Nota comprida: depois da primeira foto, use Fotografar mais itens ou Escolher mais itens, que somam os itens aos que já estão.",
            "O arquivo lido é guardado em Documentos e ligado ao lançamento ao salvar. Não vincular o documento desfaz só o vínculo.",
            "Se você corrigir banco, forma, estabelecimento, categoria, credor ou devedor antes de salvar, o app memoriza, e as correções vão junto nas próximas leituras. Elas ficam em Mais, Correções aprendidas pela IA.",
        ),
        "Saldos e limites" to listOf(
            "Em Mais, Saldos e limites. Mostra cada banco ou cartão, com o saldo ou o limite calculado. Valor negativo é o que passou do saldo ou do limite.",
            "Conta corrente: o saldo conta só o que está pago. Cartão de crédito: o limite conta também o que está pendente, porque costuma ser fatura em aberto.",
            "Ver lançamentos desta conta mostra na lista os lançamentos usados no cálculo.",
        ),
        "Documentos" to listOf(
            "Em Mais, Documentos, a mesma lista do site, com busca pelo nome e por datas.",
            "Em cada documento: Abrir, Salvar cópia, Analisar com IA, Ir ao lançamento, Vincular, Desvincular e Excluir, depois de confirmar.",
            "Guardar arquivo envia qualquer arquivo do celular, de até 25 MB.",
        ),
        "Listas" to listOf(
            "Em Mais, Gerenciar listas: adicione, renomeie ou exclua opções de banco, forma, estabelecimento, categoria, credor e devedor.",
            "Renomear troca o texto em todos os lançamentos que usam a opção, então confira antes. Excluir uma opção não altera os lançamentos antigos. Excluir um banco remove também a configuração dele em Saldos e limites.",
        ),
        "Configurações" to listOf(
            "Em Mais, Configurações: escolha quais campos aparecem em cada lançamento da lista, a ordem deles, e se o app fala a quantidade e o total ao aplicar um filtro.",
            "Importar configurações do Windows traz o arquivo de preferências do programa do Windows, para a lista ficar igual.",
        ),
        "Lançamentos duplicados" to listOf(
            "Em Mais, Limpar lançamentos duplicados: o app acha os lançamentos com tudo igual (datas, valor, banco, forma, estabelecimento, descrição, categoria, itens e pagamentos), mostra as cópias e, depois de você confirmar, exclui as cópias. O mais antigo de cada grupo fica.",
        ),
        "Sua conta" to listOf(
            "Em Mais, Sair da conta encerra a sessão neste celular. Os dados online continuam guardados.",
        ),
        "Permissões" to listOf(
            "Quem administra o sistema pode ligar ou desligar a leitura por IA e o envio de documentos da sua conta. Desligada, a função some do app.",
        ),
    )
}

@Composable
fun TelaGuia(aoVoltar: () -> Unit) {
    BackHandler(onBack = aoVoltar)
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { Titulo("Guia de uso", grande = true) }
            item { BotaoSecundario("Voltar", aoVoltar) }
            for ((titulo, paragrafos) in Guia.assuntos) {
                item { Titulo(titulo) }
                for (p in paragrafos) item { Text(p) }
            }
        }
    }
}
