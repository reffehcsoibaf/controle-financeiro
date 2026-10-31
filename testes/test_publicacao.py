"""Atualização, instalação e cópia portátil, sem janela e sem internet."""
import hashlib
import io
import os
import sys
import zipfile

import pytest

from financeiro import atualizacao, config, instalacao, preferencias


def release(tag, zip_=True, **campos):
    base = dict(tag_name=tag, body="## Novidades da versão %s\n- item %s\n\n## Como usar\nx"
                % (tag, tag), html_url="https://github.com/x/" + tag, assets=[])
    if zip_:
        base["assets"] = [
            dict(name=atualizacao.ZIP, size=9,
                 browser_download_url="https://github.com/a/" + atualizacao.ZIP),
            dict(name=atualizacao.ZIP + ".sha256",
                 browser_download_url="https://github.com/a/h")]
    base.update(campos)
    return base


def test_mais_nova_ignora_rascunho_prelancamento_e_tag_de_outro_formato():
    lanc = atualizacao.mais_nova([
        release("2026.11.5.0", draft=True), release("2026.11.4.0", prerelease=True),
        release("v1.20.1"), release("2026.11.1.0"), release("2026.11.2.1")],
        "2026.10.31.0")
    assert lanc.versao == "2026.11.2.1"
    assert lanc.url_zip.endswith(atualizacao.ZIP) and lanc.tamanho == 9
    assert [v for v, _ in lanc.historico] == ["2026.11.2.1", "2026.11.1.0"]
    assert lanc.historico[0][1] == "- item 2026.11.2.1"


def test_sem_release_nao_ha_lancamento():
    assert atualizacao.consultar(lambda url: []) is None
    assert atualizacao.mais_nova("estranho") is None


def test_comparacao_numerica_e_nao_avisar():
    lanc = atualizacao.Lancamento("2026.11.10.0")
    assert atualizacao.e_mais_nova(lanc, "2026.11.9.5")
    assert not atualizacao.e_mais_nova(lanc, "2026.11.10.0")
    assert not atualizacao.e_mais_nova(None, "2026.11.10.0")
    assert atualizacao.deve_avisar_ao_abrir(lanc, "", "2026.1.1.0")
    assert not atualizacao.deve_avisar_ao_abrir(lanc, "2026.11.10.0", "2026.1.1.0")
    assert atualizacao.deve_avisar_ao_abrir(lanc, "2026.11.9.0", "2026.1.1.0")


def test_consulta_sem_internet_vira_erro_com_motivo():
    def falha(*_):
        raise OSError("sem rede")
    with pytest.raises(atualizacao.ErroAtualizacao) as erro:
        atualizacao.consultar(falha, falha)
    assert "GitHub" in str(erro.value)


def test_consulta_usa_o_site_quando_a_api_falha():
    def falha(_):
        raise OSError("limite")
    assert atualizacao.consultar(
        falha, lambda: atualizacao.Lancamento("2027.1.1.0")).versao == "2027.1.1.0"


class Resposta(io.BytesIO):
    def __init__(self, dados):
        super().__init__(dados)
        self.headers = {"Content-Length": str(len(dados))}


def _lanc():
    return atualizacao.Lancamento(
        "2027.1.1.0", url_zip="https://github.com/a/z", url_hash="https://github.com/a/h")


def _abrir(conteudo, hash_):
    return lambda url: Resposta(conteudo if url.endswith("/z") else hash_.encode())


def test_baixar_confere_o_hash(tmp_path):
    conteudo = b"conteudo"
    certo = hashlib.sha256(conteudo).hexdigest() + " *arquivo\n"
    caminho = atualizacao.baixar(_lanc(), str(tmp_path), abrir=_abrir(conteudo, certo))
    assert open(caminho, "rb").read() == conteudo
    with pytest.raises(atualizacao.ErroAtualizacao):
        atualizacao.baixar(_lanc(), str(tmp_path), abrir=_abrir(b"outro", certo))
    assert not os.path.exists(caminho)


def test_baixar_recusa_endereco_de_fora_e_para_ao_cancelar(tmp_path):
    fora = atualizacao.Lancamento("2027.1.1.0", url_zip="https://exemplo.com/z",
                                  url_hash="https://github.com/a/h")
    with pytest.raises(atualizacao.ErroAtualizacao):
        atualizacao.baixar(fora, str(tmp_path), abrir=_abrir(b"x", "0" * 64))
    with pytest.raises(atualizacao.ErroAtualizacao):
        atualizacao.baixar(_lanc(), str(tmp_path), abrir=_abrir(b"x", "0" * 64),
                           cancelado=lambda: True)
    assert not os.listdir(tmp_path)


def _zip(caminho, nomes):
    with zipfile.ZipFile(caminho, "w") as z:
        for nome in nomes:
            z.writestr(nome, "x")
    return str(caminho)


def test_extrair_recusa_zip_sem_programa_e_com_caminho_para_fora(tmp_path):
    exe = "%s/%s" % (atualizacao.PASTA, atualizacao.EXE)
    assert atualizacao.extrair(
        _zip(tmp_path / "a.zip", [exe]), str(tmp_path / "s")).endswith(atualizacao.PASTA)
    for nomes in (["outra/coisa.exe"], [exe, "../fora.txt"]):
        with pytest.raises(atualizacao.ErroAtualizacao):
            atualizacao.extrair(_zip(tmp_path / "b.zip", nomes), str(tmp_path / "t"))
    assert not (tmp_path / "fora.txt").exists()


def test_script_de_troca_mantem_os_dados_e_escapa_porcento():
    script = atualizacao.montar_script(r"C:\P 100%\Prog", r"C:\P 100%\Prog.nova")
    assert r'set "P=C:\P 100%%\Prog"' in script
    assert "dados instalado.txt" in script
    assert atualizacao.EXE in script and "\r\n" in script


# ---- instalação ----
def _programa(pasta):
    os.makedirs(pasta / "_internal")
    (pasta / instalacao.EXE).write_text("exe")
    (pasta / "_internal" / "a.dll").write_text("dll")
    return str(pasta)


def _instalar(origem, destino):
    atalhos = []
    registros = []
    exe, avisos = instalacao.instalar(
        origem, str(destino), True, True,
        fazer_atalho=lambda *a: atalhos.append(a), fazer_registro=registros.append)
    return exe, avisos, atalhos, registros


def test_instalar_copia_marca_e_registra(tmp_path):
    origem = _programa(tmp_path / "origem")
    destino = tmp_path / "Programs" / "CF"
    exe, avisos, atalhos, registros = _instalar(origem, destino)
    assert os.path.isfile(exe) and not avisos
    assert (destino / "_internal" / "a.dll").is_file()
    assert instalacao.modo(str(destino)) == "instalado"
    assert len(atalhos) == 2 and registros == [str(destino)]


def test_instalar_de_novo_troca_so_a_instalacao(tmp_path):
    origem = _programa(tmp_path / "origem")
    destino = tmp_path / "CF"
    _instalar(origem, destino)
    (destino / "velho.txt").write_text("x")
    _instalar(origem, destino)
    assert not (destino / "velho.txt").exists()
    assert not (tmp_path / "CF.antiga").exists()


@pytest.mark.parametrize("arquivo", ["documento.txt", "dados/financeiro.db"])
def test_instalar_nunca_apaga_uma_pasta_qualquer_ou_copia_portatil(tmp_path, arquivo):
    origem = _programa(tmp_path / "origem")
    destino = tmp_path / "Pessoal"
    (destino / arquivo).parent.mkdir(parents=True)
    (destino / arquivo).write_text("importante")
    with pytest.raises(instalacao.ErroInstalacao):
        _instalar(origem, destino)
    assert (destino / arquivo).read_text() == "importante"


def test_instalar_recusa_destino_dentro_do_programa(tmp_path):
    origem = _programa(tmp_path / "origem")
    with pytest.raises(instalacao.ErroInstalacao):
        _instalar(origem, tmp_path / "origem" / "sub")


def test_atalho_que_falha_vira_aviso_e_nao_erro(tmp_path):
    def falha(*_):
        raise instalacao.ErroInstalacao("Sem atalho.")
    _, avisos = instalacao.instalar(
        _programa(tmp_path / "o"), str(tmp_path / "d"),
        fazer_atalho=falha, fazer_registro=lambda _: None)
    assert avisos == ["Sem atalho."]


def test_copia_portatil_tem_pasta_de_dados_e_nao_e_instalacao(tmp_path):
    origem = _programa(tmp_path / "origem")
    alvo = instalacao.criar_portatil(origem, str(tmp_path / "pendrive"))
    assert os.path.isdir(os.path.join(alvo, "dados"))
    assert instalacao.modo(alvo) == "portatil"
    assert config.dados_portateis(alvo) == os.path.join(alvo, "dados")
    with pytest.raises(instalacao.ErroInstalacao):     # já existe e não está vazia
        instalacao.criar_portatil(origem, str(tmp_path / "pendrive"))


def test_pacote_do_instalador_e_extraido_e_apagado(tmp_path, monkeypatch):
    monkeypatch.setenv("LOCALAPPDATA", str(tmp_path))
    arquivo = _zip(tmp_path / "p.zip", ["%s/%s" % (atualizacao.PASTA, atualizacao.EXE)])
    with instalacao.extrair_pacote(arquivo) as programa:
        assert os.path.isfile(os.path.join(programa, atualizacao.EXE))
    assert not (tmp_path / "ControleFinanceiro-instalador").exists()


def test_faixa_leva_o_avanco_para_o_trecho():
    vistos = []
    etapa = instalacao.faixa(lambda p, t: vistos.append((p, t)), 40, 100)
    etapa(50, "a")
    etapa(None, "b")
    assert vistos == [(70.0, "a"), (None, "b")]
    assert instalacao.faixa(None, 0, 1) is None


def test_desinstalar_recusa_o_que_nao_e_instalacao(tmp_path):
    with pytest.raises(instalacao.ErroInstalacao):
        instalacao.desinstalar(str(tmp_path))


# ---- pasta dos dados ----
def test_portatil_guarda_os_dados_ao_lado_do_executavel(tmp_path, monkeypatch):
    (tmp_path / "dados").mkdir()
    monkeypatch.delenv("FINANCEIRO_DADOS", raising=False)
    monkeypatch.setattr(sys, "frozen", True, raising=False)
    monkeypatch.setattr(sys, "executable", str(tmp_path / atualizacao.EXE))
    assert config.pasta_dados() == str(tmp_path / "dados")


def test_instalado_guarda_os_dados_no_appdata(tmp_path, monkeypatch):
    monkeypatch.delenv("FINANCEIRO_DADOS", raising=False)
    monkeypatch.setenv("APPDATA", str(tmp_path / "ad"))
    monkeypatch.setattr(sys, "frozen", True, raising=False)
    monkeypatch.setattr(sys, "executable", str(tmp_path / "prog" / atualizacao.EXE))
    assert config.pasta_dados() == str(tmp_path / "ad" / "ControleFinanceiro")


# ---- o que a tela lembra ----
def test_tela_e_posicao_ficam_guardadas(tmp_path, monkeypatch):
    monkeypatch.setenv("FINANCEIRO_DADOS", str(tmp_path))
    assert (preferencias.ler_tipo(), preferencias.ler_situacao()) == ("", "")
    assert preferencias.ler_posicao() == (None, 0, 0)
    preferencias.salvar_ordem("antigos")
    preferencias.salvar_tela("saida", "pendente", -7, 12, 3)
    assert preferencias.ler_tipo() == "saida"
    assert preferencias.ler_situacao() == "pendente"
    assert preferencias.ler_posicao() == (-7, 12, 3)
    assert preferencias.ler_ordem() == "antigos"


def test_tela_com_arquivo_estranho_volta_ao_inicio(tmp_path, monkeypatch):
    monkeypatch.setenv("FINANCEIRO_DADOS", str(tmp_path))
    (tmp_path / "preferencias.json").write_text(
        '{"tipo_tabela": "x", "situacao_tabela": 3, '
        '"posicao_tabela": {"lancamento": "a", "linha": -1, "coluna": true}}',
        encoding="utf-8")
    assert (preferencias.ler_tipo(), preferencias.ler_situacao()) == ("", "")
    assert preferencias.ler_posicao() == (None, 0, 0)


def test_versao_ignorada_fica_guardada(tmp_path, monkeypatch):
    monkeypatch.setenv("FINANCEIRO_DADOS", str(tmp_path))
    assert preferencias.ler_atualizacao_ignorada() == ""
    preferencias.salvar_atualizacao_ignorada("2027.1.1.0")
    assert preferencias.ler_atualizacao_ignorada() == "2027.1.1.0"


def test_versao_em_uso_segue_o_formato_e_tem_novidades():
    from financeiro import __version__
    from financeiro.gerenciar import ler_assuntos
    assert atualizacao.como_tupla(__version__)
    assert "Versão " + __version__ in [t for t, _ in ler_assuntos("novidades.txt")]
