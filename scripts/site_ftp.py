"""Baixa ou envia o código do site de Diego por FTP, com a senha pedida numa janela.

baixar: copia arquivos de código e texto (sem áudios e mídias grandes) para uma pasta local.
enviar: envia somente os arquivos indicados, relativos à pasta local, para o mesmo caminho remoto.
"""

from __future__ import annotations

import argparse
import ftplib
import io
import subprocess
import time
import urllib.error
import urllib.request
from pathlib import Path, PurePosixPath

from publicar_devocional import ask_password_window, connect

SKIP_EXTENSIONS = {
    ".mp3", ".m4a", ".ogg", ".wav", ".flac", ".aac", ".mp4", ".webm", ".zip", ".apk", ".exe",
    ".rar", ".7z", ".iso",
}
MAX_SIZE = 3 * 1024 * 1024


SKIP_FOLDERS = ("biblia/data/",)


class Session:
    """Mantém uma conexão FTP e reconecta com a mesma senha quando o servidor derruba a sessão."""

    def __init__(self, host: str, user: str, password: str):
        self.host, self.user, self.password = host, user, password
        self.client = None
        self.reconnect()

    def reconnect(self):
        if self.client is not None:
            try:
                self.client.close()
            except Exception:
                pass
        self.client, protocol = connect(self.host, 21, self.user, self.password)
        return protocol

    def run(self, action, attempts: int = 4):
        for attempt in range(attempts):
            try:
                return action(self.client)
            except (EOFError, OSError, ftplib.error_temp, ftplib.error_reply):
                if attempt == attempts - 1:
                    raise
                time.sleep(2 + attempt * 2)
                self.reconnect()

    def close(self):
        try:
            self.client.quit()
        except Exception:
            self.client.close()


def list_files(session: Session) -> list[tuple[str, int]]:
    """Lista o FTP pasta por pasta, reconectando quando necessário."""
    files, pending = [], [""]
    while pending:
        folder = pending.pop()
        if any(f"{folder}/".startswith(skip) for skip in SKIP_FOLDERS):
            continue
        entries = session.run(lambda client: list(client.mlsd(folder or "/", facts=["type", "size"])))
        for name, facts in entries:
            if name in {".", ".."}:
                continue
            path = f"{folder}/{name}" if folder else name
            if facts.get("type") == "dir":
                pending.append(path)
            elif facts.get("type") == "file":
                files.append((path, int(facts.get("size", 0))))
    return sorted(files)


def download(session: Session, local: Path) -> None:
    skipped = []
    count = 0
    for path, size in list_files(session):
        suffix = PurePosixPath(path).suffix.lower()
        if suffix in SKIP_EXTENSIONS or size > MAX_SIZE or "/posts.backup-" in f"/{path}":
            skipped.append(f"{path} ({size} bytes)")
            continue
        target = local / path
        if target.is_file() and target.stat().st_size == size:
            continue
        target.parent.mkdir(parents=True, exist_ok=True)

        def fetch(client, path=path):
            buffer = io.BytesIO()
            client.retrbinary(f"RETR /{path}", buffer.write)
            return buffer.getvalue()

        target.write_bytes(session.run(fetch))
        count += 1
    skipped.append("Pastas não baixadas (dados gerados da Bíblia online): " + ", ".join(SKIP_FOLDERS))
    (local / "_ignorados_no_download.txt").write_text("\n".join(skipped), encoding="utf-8")
    print(f"Baixados agora: {count} arquivos. Ignorados (mídia ou grandes): {len(skipped) - 1}.")


def upload(client, local: Path, files: list[str]) -> None:
    for relative in files:
        source = local / relative
        remote = PurePosixPath("/" + relative.replace("\\", "/"))
        client.cwd("/")
        for part in remote.parent.parts[1:]:
            try:
                client.cwd(part)
            except ftplib.error_perm:
                client.mkd(part)
                client.cwd(part)
        with source.open("rb") as handle:
            client.storbinary(f"STOR {remote.name}", handle)
        print(f"Enviado: {remote}")


SITE = "https://www.audiogames.com.br/diego/"
MUST_OPEN = (
    "", "devocionais/", "devocionais/?post=ninguem-viu-mas-deus-viu", "musicas/", "programas/",
    "sobre/", "contato/", "painel/", "sitemap.php", "biblia/", "assets/site.css", "assets/favicon.svg",
    "assets/og-diego-grando.jpg", "meu-jogo-paises/", "futebol-acessivel/", "devocionais/posts.json",
)
MUST_BLOCK = ("meu-jogo-paises/dados_jogadores.json", "phpmailer/get_oauth_token.php", "contador.txt")


def http_status(url: str) -> tuple[int, str]:
    """Segue redirecionamentos (até 8) e devolve o código final e o endereço final."""
    request = urllib.request.Request(url, headers={"User-Agent": "Site-Diego-Verificador"})
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return response.status, response.geturl()
    except urllib.error.HTTPError as error:
        return error.code, url
    except Exception as error:  # redirecionamento em ciclo, falha de rede etc.
        return 0, f"{url} ({error})"


def verify_site() -> list[str]:
    problems = []
    for path in MUST_OPEN:
        code, final = http_status(SITE + path + ("&" if "?" in path else "?") + f"verificacao={int(time.time())}")
        if code != 200:
            problems.append(f"{path or 'página inicial'} respondeu {code} ({final})")
    for path in MUST_BLOCK:
        code, _final = http_status(SITE + path)
        if code == 200:
            problems.append(f"{path} continua aberto ao público")
    # Endereços lidos pelos apps da Bíblia: precisam responder sem nenhum redirecionamento.
    for path in ("devocionais/posts.json", "louvores/louvores.json"):
        url = f"https://audiogames.com.br/diego/{path}"
        code, final = http_status(url)
        if code != 200 or final != url:
            problems.append(f"os apps da Bíblia não conseguem ler {path} ({code}, {final})")
    code, final = http_status("http://audiogames.com.br/diego/")
    if code != 200 or not final.startswith("https://www.audiogames.com.br/diego/"):
        problems.append(f"o endereço sem https/www terminou em {final} com código {code}")
    return problems


def git_show(local: Path, commit: str, relative: str) -> bytes:
    return subprocess.run(["git", "-C", str(local), "show", f"{commit}:{relative}"],
                          check=True, capture_output=True).stdout


def publish(session: Session, local: Path, base: str) -> None:
    """Confere que o servidor não mudou, envia, testa o site e desfaz tudo se algo quebrar."""
    changes = subprocess.run(["git", "-C", str(local), "diff", "--name-status", base, "HEAD"],
                             check=True, capture_output=True, text=True).stdout.split("\n")
    modified, added = [], []
    for line in filter(None, changes):
        status, relative = line.split("\t", 1)
        (added if status == "A" else modified).append(relative)

    for relative in modified:
        current = session.run(lambda client, r=relative: _retrieve(client, r))
        if current != git_show(local, base, relative):
            raise SystemExit(f"/{relative} mudou no servidor depois do download. Nada foi enviado.")
    print(f"Servidor conferido: {len(modified)} arquivos alterados e {len(added)} novos.")

    # .htaccess por último: se o redirecionamento falhar, o resto já está no lugar para o teste.
    order = sorted(modified + added, key=lambda name: name.endswith(".htaccess"))
    session.run(lambda client: upload(client, local, order))
    time.sleep(3)
    problems = verify_site()
    if not problems:
        print("Verificação no ar: todas as páginas abriram e os arquivos sensíveis estão bloqueados.")
        return

    print("Algo não funcionou no ar:")
    for problem in problems:
        print(" -", problem)
    print("Desfazendo o envio e voltando ao site original...")

    def rollback(client):
        for relative in modified:
            client.storbinary(f"STOR /{relative}", io.BytesIO(git_show(local, base, relative)))
        for relative in added:
            try:
                client.delete(f"/{relative}")
            except ftplib.error_perm:
                pass

    session.run(rollback)
    remaining = verify_site()
    print("Site original restaurado." if len(remaining) <= len(MUST_BLOCK) + 1 else "Atenção: confira o site.")
    raise SystemExit(1)


def _retrieve(client, relative: str) -> bytes:
    buffer = io.BytesIO()
    client.retrbinary(f"RETR /{relative}", buffer.write)
    return buffer.getvalue()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("acao", choices=("baixar", "enviar", "publicar"))
    parser.add_argument("--base", default="", help="Commit do original baixado (para publicar).")
    parser.add_argument("pasta", type=Path)
    parser.add_argument("arquivos", nargs="*")
    parser.add_argument("--host", default="ftp.audiogames.com.br")
    parser.add_argument("--user", default="diego@audiogames.com.br")
    args = parser.parse_args()
    password = ask_password_window(
        "Site do Diego — senha do FTP para " + ("baixar o código" if args.acao == "baixar" else "enviar as correções"),
        "Baixar" if args.acao == "baixar" else "Enviar",
    )
    session = Session(args.host, args.user, password)
    try:
        print("Conectado.")
        if args.acao == "baixar":
            args.pasta.mkdir(parents=True, exist_ok=True)
            download(session, args.pasta)
        elif args.acao == "publicar":
            publish(session, args.pasta, args.base)
        else:
            upload(session.client, args.pasta, args.arquivos)
    finally:
        session.close()


if __name__ == "__main__":
    main()
