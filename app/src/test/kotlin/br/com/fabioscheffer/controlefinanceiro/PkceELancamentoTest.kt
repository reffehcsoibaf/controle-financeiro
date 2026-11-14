package br.com.fabioscheffer.controlefinanceiro

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PkceELancamentoTest {
    @Test fun desafioConfereComOExemploDaRfc7636() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            Pkce.desafioDe("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test fun cadaLoginTemVerificadorProprioEValido() {
        val a = Pkce.novo()
        val b = Pkce.novo()
        assertNotEquals(a.verificador, b.verificador)
        assertTrue(a.verificador.length in 43..128)
        assertTrue(a.verificador.matches(Regex("[A-Za-z0-9_-]+")))
        assertEquals(Pkce.desafioDe(a.verificador), a.desafio)
    }

    @Test fun lancamentoLidoDoSupabaseComNulosEPagoBooleano() {
        val l = Lancamento.fromJson(
            JSONObject(
                """{"id":7,"valor":12.5,"pago":true,"banco":null,"estabelecimento":"Padaria",
                    "data_vencimento":"2026-01-02","parcela":null}"""
            )
        )
        assertEquals(7L, l.id)
        assertEquals(12.5, l.valor, 0.0)
        assertTrue(l.pago)
        assertNull(l.banco)
        assertEquals("Padaria", l.estabelecimento)
    }

    @Test fun pagoComoNumeroTambemFunciona() {
        val l = Lancamento.fromJson(JSONObject("""{"id":1,"valor":1,"pago":0}"""))
        assertFalse(l.pago)
    }
}
