"""Publica um devocional no site por FTP, no mesmo posts.json lido pela Bíblia AGB.

O arquivo de entrada é texto UTF-8: a primeira linha é o título e as demais formam
o conteúdo (versículo em cima, reflexão embaixo). A senha é pedida na hora e nunca
é gravada. Antes de enviar, o script confere que o posts.json do FTP é o mesmo
publicado no site, guarda uma cópia de segurança no servidor e, no fim, confirma
pelo endereço público que o devocional novo aparece em primeiro lugar.
"""

from __future__ import annotations

import argparse
import ftplib
import getpass
import io
import json
import sys
import time
from datetime import datetime
from pathlib import Path
from urllib.request import Request, urlopen

PUBLIC_URL = "https://audiogames.com.br/diego/devocionais/posts.json"
REMOTE_CANDIDATES = (
    "devocionais/posts.json",
    "diego/devocionais/posts.json",
    "public_html/diego/devocionais/posts.json",
    "www/diego/devocionais/posts.json",
)


def connect(host: str, port: int, user: str, password: str):
    """Prefere FTPS explícito e usa FTP comum apenas quando o servidor não oferece TLS."""
    secure = ftplib.FTP_TLS(timeout=40)
    try:
        secure.connect(host, port)
        secure.auth()
        secure.login(user, password)
        secure.prot_p()
        return secure, "FTPS"
    except Exception:
        try:
            secure.close()
        except Exception:
            pass
    plain = ftplib.FTP(timeout=40)
    plain.connect(host, port)
    plain.login(user, password)
    return plain, "FTP"


def ask_password_window() -> str:
    """Pede a senha numa janela nativa do Qt, legível pelo NVDA e pronta para digitar."""
    from PySide6.QtCore import Qt, QTimer
    from PySide6.QtWidgets import QApplication, QInputDialog, QLineEdit

    app = QApplication.instance() or QApplication([])
    dialog = QInputDialog()
    dialog.setWindowTitle("Publicar devocional — senha do FTP")
    dialog.setLabelText("Digite a senha do FTP do site e pressione Enter:")
    dialog.setTextEchoMode(QLineEdit.Password)
    dialog.setOkButtonText("Publicar")
    dialog.setCancelButtonText("Cancelar")
    dialog.setWindowFlag(Qt.WindowStaysOnTopHint, True)

    def bring_to_front():
        dialog.raise_()
        dialog.activateWindow()

    QTimer.singleShot(200, bring_to_front)
    accepted = dialog.exec() == QInputDialog.Accepted
    password = dialog.textValue()
    app.processEvents()
    if not accepted or not password:
        raise SystemExit("Publicação cancelada. Nada foi enviado.")
    return password


def download(client, path: str) -> bytes | None:
    """Baixa um arquivo remoto ou devolve None quando ele não existe nesse caminho."""
    buffer = io.BytesIO()
    try:
        client.retrbinary(f"RETR /{path}", buffer.write)
    except ftplib.error_perm:
        return None
    return buffer.getvalue()


def fetch_public() -> bytes:
    """Lê a cópia publicada, sem cache, para comparar com o arquivo do FTP."""
    request = Request(f"{PUBLIC_URL}?t={int(time.time())}", headers={"User-Agent": "BibliaAGB-Publicador"})
    with urlopen(request, timeout=30) as response:
        return response.read()


def build_post(text_file: Path) -> dict:
    """Converte o arquivo de texto no mesmo formato dos devocionais já publicados."""
    lines = text_file.read_text(encoding="utf-8-sig").strip().splitlines()
    if len(lines) < 3:
        raise SystemExit("O arquivo precisa do título na primeira linha e do texto nas seguintes.")
    now = datetime.now()
    stamp = now.strftime("%Y-%m-%d %H:%M:%S")
    return {
        "id": now.strftime("%Y%m%d%H%M%S") + f"{now.microsecond // 1000:03d}",
        "titulo": lines[0].strip(),
        "status": "publicado",
        "data": stamp,
        "atualizado_em": stamp,
        # O painel do site grava quebras de linha do Windows; mantemos o mesmo padrão.
        "conteudo": "\r\n".join(line.rstrip() for line in lines[1:]).strip(),
    }


def serialize(posts: list) -> bytes:
    """Reproduz exatamente a formatação atual do posts.json (4 espaços, UTF-8 legível)."""
    return json.dumps(posts, ensure_ascii=False, indent=4).encode("utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("arquivo", type=Path, help="Texto do devocional: título na primeira linha.")
    parser.add_argument("--host", default="ftp.audiogames.com.br")
    parser.add_argument("--port", type=int, default=21)
    parser.add_argument("--user", default="diego@audiogames.com.br")
    parser.add_argument("--simular", action="store_true", help="Mostra o resultado sem conectar nem enviar.")
    parser.add_argument("--janela", action="store_true", help="Pede a senha numa janela do Windows.")
    args = parser.parse_args()

    post = build_post(args.arquivo)
    public = fetch_public()
    posts = json.loads(public.decode("utf-8"))
    if any(item.get("titulo") == post["titulo"] for item in posts):
        raise SystemExit(f"Já existe um devocional publicado com o título: {post['titulo']}")
    updated = serialize([post, *posts])
    json.loads(updated.decode("utf-8"))

    print(f"Título: {post['titulo']}")
    print(f"Data: {post['data']}")
    print(f"Devocionais no site: {len(posts)}. Depois da publicação: {len(posts) + 1}.")
    print("Início do texto:", post["conteudo"].split("\r\n")[0])
    if args.simular:
        print("Simulação concluída. Nada foi enviado.")
        return

    password = ask_password_window() if args.janela else getpass.getpass("Senha FTP: ")
    client, protocol = connect(args.host, args.port, args.user, password)
    try:
        print(f"Conectado por {protocol}.")
        remote_path = None
        for candidate in REMOTE_CANDIDATES:
            content = download(client, candidate)
            if content is not None:
                if content != public:
                    raise SystemExit(
                        f"O arquivo /{candidate} é diferente do publicado no site. Nada foi alterado."
                    )
                remote_path = candidate
                break
        if remote_path is None:
            raise SystemExit("Não encontrei o posts.json no FTP. Nada foi alterado.")
        print(f"Arquivo encontrado: /{remote_path}")

        folder, name = remote_path.rsplit("/", 1)
        client.cwd(f"/{folder}")
        backup = f"posts.backup-{datetime.now().strftime('%Y%m%d-%H%M%S')}.json"
        client.storbinary(f"STOR {backup}", io.BytesIO(public))
        print(f"Cópia de segurança criada: /{folder}/{backup}")
        client.storbinary(f"STOR {name}", io.BytesIO(updated))
        print("posts.json atualizado.")
    finally:
        try:
            client.quit()
        except Exception:
            client.close()

    time.sleep(2)
    published = json.loads(fetch_public().decode("utf-8"))
    if published and published[0].get("id") == post["id"]:
        print("Confirmado no site: o novo devocional é o primeiro da lista.")
        print("A Bíblia AGB no Android e no Windows vai avisar na próxima verificação.")
    else:
        print("Atenção: o site ainda não mostra o novo devocional. Confira em alguns minutos.", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
