from financeiro import colunas, preferencias


def test_padrao_tem_todas_as_colunas_uma_vez():
    chaves = [k for k, _ in colunas.padrao()]
    assert sorted(chaves) == sorted(c.chave for c in colunas.TODAS)
    assert [c.chave for c in colunas.visiveis(colunas.padrao())] == list(
        colunas.PADRAO_VISIVEIS)


def test_ordem_e_visibilidade_sao_respeitadas():
    escolha = colunas.normalizar([["valor", True], ["forma", False],
                                  ["credor", True]])
    assert [c.chave for c in colunas.visiveis(escolha)] == [
        "situacao", "valor", "credor"]
    assert {k for k, _ in escolha} == {c.chave for c in colunas.TODAS}


def test_situacao_fica_sempre_em_primeiro_mesmo_em_arquivo_antigo():
    # "tipo" existiu em versões antigas e é ignorada
    antigo = [["tipo", True], ["situacao", True], ["valor", True],
              ["forma", True]]
    assert [k for k, v in colunas.normalizar(antigo) if v] == [
        "situacao", "valor", "forma"]
    oculta = [["situacao", False], ["valor", True]]
    assert [k for k, v in colunas.normalizar(oculta) if v] == [
        "situacao", "valor"]


def test_arquivo_estranho_nao_quebra():
    for ruim in (None, "x", [1, 2], [["nao_existe", True]],
                 [["forma", False], ["valor", False]]):
        assert colunas.normalizar(ruim) == colunas.padrao()
    assert [k for k, _ in colunas.normalizar(
        [["valor", True], ["valor", False]])].count("valor") == 1


def test_falar_quantidade_comeca_ligado_e_nao_apaga_as_colunas(
        tmp_path, monkeypatch):
    monkeypatch.setenv("FINANCEIRO_DADOS", str(tmp_path))
    assert preferencias.ler_falar_quantidade() is True
    escolha = colunas.normalizar([["valor", True]])
    preferencias.salvar_colunas(escolha)
    preferencias.salvar_falar_quantidade(False)
    assert preferencias.ler_falar_quantidade() is False
    assert preferencias.ler_colunas() == escolha


def test_falar_total_comeca_desligado_e_e_guardado(tmp_path, monkeypatch):
    monkeypatch.setenv("FINANCEIRO_DADOS", str(tmp_path))
    assert preferencias.ler_falar_total() is False
    preferencias.salvar_falar_total(True)
    assert preferencias.ler_falar_total() is True
    assert preferencias.ler_falar_quantidade() is True


def test_ordem_da_tabela_fica_guardada(tmp_path, monkeypatch):
    monkeypatch.setenv("FINANCEIRO_DADOS", str(tmp_path))
    assert preferencias.ler_ordem() == "recentes"
    preferencias.salvar_ordem("antigos")
    assert preferencias.ler_ordem() == "antigos"


def test_preferencia_sobrevive_a_reabrir(tmp_path, monkeypatch):
    monkeypatch.setenv("FINANCEIRO_DADOS", str(tmp_path))
    assert preferencias.ler_colunas() == colunas.padrao()
    escolha = colunas.normalizar([["valor", True], ["descricao", True]])
    preferencias.salvar_colunas(escolha)
    assert preferencias.ler_colunas() == escolha
    (tmp_path / "preferencias.json").write_text("{quebrado", encoding="utf-8")
    assert preferencias.ler_colunas() == colunas.padrao()
