"""Lista ou publica a versão web por FTP/FTPS sem gravar credenciais."""

from __future__ import annotations

import argparse
import ftplib
import getpass
from pathlib import Path, PurePosixPath


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


def ensure_directory(client, path: PurePosixPath) -> None:
    """Cria somente as pastas ausentes e nunca apaga conteúdo remoto."""
    client.cwd("/")
    for part in path.parts:
        if part in {"", "/", "."}:
            continue
        try:
            client.cwd(part)
        except ftplib.error_perm:
            client.mkd(part)
            client.cwd(part)


def upload_tree(client, local: Path, remote: PurePosixPath) -> int:
    """Envia a árvore local preservando outros arquivos que já existam no servidor."""
    count = 0
    for path in sorted(item for item in local.rglob("*") if item.is_file()):
        relative = PurePosixPath(path.relative_to(local).as_posix())
        destination = remote / relative
        ensure_directory(client, destination.parent)
        with path.open("rb") as source:
            client.storbinary(f"STOR {destination.name}", source, blocksize=128 * 1024)
        count += 1
        print(f"Enviado: {destination}")
    return count


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", required=True)
    parser.add_argument("--port", type=int, default=21)
    parser.add_argument("--user", required=True)
    parser.add_argument("--local", type=Path)
    parser.add_argument("--remote", default=".")
    parser.add_argument("--list-only", action="store_true")
    args = parser.parse_args()
    password = getpass.getpass("Senha FTP: ")
    client, protocol = connect(args.host, args.port, args.user, password)
    try:
        print(f"Conectado por {protocol}. Pasta inicial: {client.pwd()}")
        if args.list_only:
            for item in client.nlst():
                print(item)
            return
        if not args.local or not args.local.is_dir():
            parser.error("Informe uma pasta local válida para publicar.")
        total = upload_tree(client, args.local.resolve(), PurePosixPath(args.remote))
        print(f"Publicação concluída: {total} arquivos enviados para {args.remote}")
    finally:
        try:
            client.quit()
        except Exception:
            client.close()


if __name__ == "__main__":
    main()
