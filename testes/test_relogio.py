"""Vale a alteração mais recente, desde que os horários sejam confiáveis."""
import sqlite3

from financeiro import banco as banco_mod, nuvem, sincro
from financeiro.banco import Banco

# Agora, para o computador: 12:00:00 UTC
AGORA = 1793361600.0
MEIO_DIA = "2026-10-30T12:00:00+00:00"


def t(minutos):
    return "2026-10-30T%02d:%02d:00+00:00" % (11 + (60 + minutos) // 60,
                                              (60 + minutos) % 60)


def agora_de(iso):
    return sincro._instante(iso)


def test_vale_a_mais_recente():
    a = agora_de(t(0))
    assert sincro.decidir(t(-5), t(-20), 0, a) == sincro.LOCAL
    assert sincro.decidir(t(-20), t(-5), 0, a) == sincro.SERVIDOR


def test_relogio_desacertado_nao_decide_sozinho():
    a = agora_de(t(0))
    assert sincro.decidir(t(-5), t(-20), 600, a) == sincro.PERGUNTAR
    assert sincro.decidir(t(-5), t(-20), -600, a) == sincro.PERGUNTAR
    assert sincro.decidir(t(-5), t(-20), None, a) == sincro.PERGUNTAR


def test_diferenca_pequena_do_relogio_e_descontada():
    # O computador está 60 s adiantado: editou às 11:59:30 pelo relógio dele,
    # que na verdade eram 11:58:30; o servidor mudou às 11:59:00.
    a = agora_de(t(0))
    local = "2026-10-30T11:59:30+00:00"
    servidor = "2026-10-30T11:59:00+00:00"
    assert sincro.decidir(local, servidor, -60, a) == sincro.SERVIDOR
    assert sincro.decidir(local, servidor, 0, a) == sincro.LOCAL


def test_alteracao_no_futuro_nao_e_aceita():
    # O relógio estava adiantado ao editar e foi acertado depois
    a = agora_de(t(0))
    amanha = "2026-10-31T12:00:00+00:00"
    assert sincro.decidir(amanha, t(-5), 0, a) == sincro.PERGUNTAR


def test_alteracoes_quase_juntas_perguntam():
    a = agora_de(t(0))
    assert sincro.decidir("2026-10-30T11:50:05+00:00",
                          "2026-10-30T11:50:00+00:00", 0, a) == sincro.PERGUNTAR


def test_sem_hora_pergunta():
    a = agora_de(t(0))
    assert sincro.decidir(None, t(-5), 0, a) == sincro.PERGUNTAR
    assert sincro.decidir(t(-5), "lixo", 0, a) == sincro.PERGUNTAR


def test_formatos_de_hora_do_servidor():
    assert sincro._instante("2026-10-30T19:42:47.114+00:00")
    assert sincro._instante("2026-10-30T19:42:47.114123+00:00")


def test_fuso_nao_muda_a_comparacao():
    # 9h em Brasília e 12h UTC são o mesmo instante
    a = agora_de(t(0))
    assert sincro._instante("2026-10-30T09:00:00-03:00") == sincro._instante(
        "2026-10-30T12:00:00+00:00")
    assert sincro.decidir("2026-10-30T08:55:00-03:00", t(-20), 0, a) == sincro.LOCAL
    assert sincro.decidir("2026-10-30T08:40:00-03:00", t(-5), 0, a) == sincro.SERVIDOR


def test_alteracao_guardada_em_utc():
    assert banco_mod.agora_iso().endswith("+00:00")


def test_defasagem_vem_do_cabecalho_date(monkeypatch):
    monkeypatch.setattr(nuvem.time, "time", lambda: 1000.0)
    nuvem._anotar_relogio("Thu, 01 Jan 1970 00:17:40 GMT")   # 1060 s
    assert nuvem.defasagem() == 60.0
    nuvem._anotar_relogio("cabeçalho ruim")
    nuvem._anotar_relogio(None)
    assert nuvem.defasagem() == 60.0


def test_alteracoes_guardam_a_hora(tmp_path):
    b = Banco(str(tmp_path / "h.db"))
    novo = b.salvar_lancamento(dict(data_origem="2026-10-01", valor=-1.0), [])
    assert b.envios()[0]["editado_em"]
    b.excluir_lancamento(novo)
    b.fechar()


def test_banco_antigo_ganha_a_coluna_sem_perder_a_fila(tmp_path):
    caminho = str(tmp_path / "a.db")
    con = sqlite3.connect(caminho)
    con.executescript("""
        CREATE TABLE pendencias_pago (lancamento_id INTEGER PRIMARY KEY,
            pago INTEGER NOT NULL, base_updated_at TEXT);
        INSERT INTO pendencias_pago VALUES (7, 1, 'v1');
        CREATE TABLE pendencias_lancamento (lancamento_id INTEGER PRIMARY KEY,
            operacao TEXT NOT NULL, client_uuid TEXT, base_updated_at TEXT,
            versao INTEGER NOT NULL, conflito INTEGER NOT NULL DEFAULT 0,
            servidor TEXT);
    """)
    con.commit()
    con.close()
    b = Banco(caminho)
    p = b.pendencias()[0]
    assert p["lancamento_id"] == 7 and p["editado_em"] is None
    b.fechar()
