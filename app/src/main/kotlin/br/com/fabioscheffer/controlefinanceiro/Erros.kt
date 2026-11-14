package br.com.fabioscheffer.controlefinanceiro

class SemConexao(mensagem: String = "") : RuntimeException(mensagem)
class SessaoExpirada(mensagem: String = "") : RuntimeException(mensagem)
class AcessoNegado : RuntimeException()
class ErroServidor(mensagem: String) : RuntimeException(mensagem)
class ErroLeituraIA(mensagem: String) : RuntimeException(mensagem)

/** O arquivo escolhido não serve para a leitura; a mensagem é para a pessoa. */
class ErroDocumento(mensagem: String) : RuntimeException(mensagem)
