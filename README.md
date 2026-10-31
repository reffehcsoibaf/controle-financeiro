# Controle Financeiro para Windows

Programa nativo para Windows, feito para uso com leitor de tela, ligado ao mesmo
Supabase do site. Funciona sem internet e sincroniza quando a conexão volta.

Este código fica no ramo `windows`, separado do site, que é publicado a partir
do ramo `main`. As regras de cálculo seguem as do `index.html` do site; quando
uma mudar lá, precisa mudar aqui também (`financeiro/regras.py`).

## Rodar

```
python -m venv venv
venv\Scripts\pip install -r requirements.txt
iniciar.bat
```

## Testar

```
venv\Scripts\python -m pytest testes
```

`ferramentas/capturar_tela.py` exercita as janelas sem mostrar nada na tela. Só
com `--mostrar` elas aparecem.

## Publicar

```
venv\Scripts\python ferramentas\empacotar.py
venv\Scripts\python ferramentas\publicar.py
venv\Scripts\python ferramentas\publicar.py --publicar
```

O primeiro gera em `lancamento/` o instalador, o zip que o atualizador baixa e o
SHA-256, e confere os executáveis sem abrir janela. O segundo só monta as notas.
O terceiro cria a Release, com a tag no ramo `windows`, o que não muda o site. A
versão fica em `financeiro/__init__.py`, no formato ano.mes.dia.dígito, e precisa
de uma seção em `financeiro/novidades.txt`.

## Banco

`banco/` guarda, em ordem, os acréscimos já aplicados no Supabase.
