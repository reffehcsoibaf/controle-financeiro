# Controle Financeiro para Windows

Programa nativo para Windows, feito para uso com leitor de tela, ligado ao mesmo
Supabase do site. Funciona sem internet e sincroniza quando a conexão volta.

Este código fica no ramo `windows`, separado do site, que é publicado a partir
do ramo `main`. As regras de cálculo seguem as do `index.html` do site; quando
uma mudar lá, precisa mudar aqui também (`financeiro/regras.py`).

## Rodar

```
python -m venv venv
venv\Scripts\pip install wxPython accessible_output2 pytest
iniciar.bat
```

## Testar

```
venv\Scripts\python -m pytest testes
```

`ferramentas/capturar_tela.py` exercita as janelas sem mostrar nada na tela. Só
com `--mostrar` elas aparecem.

## Banco

`banco/` guarda, em ordem, os acréscimos já aplicados no Supabase.
