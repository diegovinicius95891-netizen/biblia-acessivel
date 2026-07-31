"""Armazena a chave da API criptografada para a conta atual do Windows."""

from __future__ import annotations

import base64
import ctypes
from ctypes import wintypes


CRYPTPROTECT_UI_FORBIDDEN = 0x1


class _DataBlob(ctypes.Structure):
    """Representa o bloco binário esperado pela API de proteção do Windows."""

    _fields_ = [("cbData", wintypes.DWORD), ("pbData", ctypes.POINTER(ctypes.c_byte))]


class SecureStoreError(RuntimeError):
    """Indica que o Windows não conseguiu proteger ou recuperar a chave."""


def protect_text(value: str) -> str:
    """Criptografa texto com DPAPI e devolve Base64 adequado ao QSettings."""
    raw = value.encode("utf-8")
    buffer = ctypes.create_string_buffer(raw)
    source = _DataBlob(len(raw), ctypes.cast(buffer, ctypes.POINTER(ctypes.c_byte)))
    protected = _DataBlob()
    crypt32 = ctypes.windll.crypt32
    if not crypt32.CryptProtectData(
        ctypes.byref(source),
        "Bíblia Acessível",
        None,
        None,
        None,
        CRYPTPROTECT_UI_FORBIDDEN,
        ctypes.byref(protected),
    ):
        raise SecureStoreError("O Windows não conseguiu criptografar a chave da API.")
    try:
        encrypted = ctypes.string_at(protected.pbData, protected.cbData)
        return base64.b64encode(encrypted).decode("ascii")
    finally:
        ctypes.windll.kernel32.LocalFree(protected.pbData)


def unprotect_text(value: str) -> str:
    """Descriptografa uma chave vinculada à mesma conta do Windows."""
    try:
        raw = base64.b64decode(value.encode("ascii"), validate=True)
    except (ValueError, UnicodeEncodeError):
        raise SecureStoreError("A chave da API salva está corrompida.") from None
    buffer = ctypes.create_string_buffer(raw)
    source = _DataBlob(len(raw), ctypes.cast(buffer, ctypes.POINTER(ctypes.c_byte)))
    clear = _DataBlob()
    crypt32 = ctypes.windll.crypt32
    if not crypt32.CryptUnprotectData(
        ctypes.byref(source), None, None, None, None, CRYPTPROTECT_UI_FORBIDDEN, ctypes.byref(clear)
    ):
        raise SecureStoreError("O Windows não conseguiu abrir a chave da API salva.")
    try:
        return ctypes.string_at(clear.pbData, clear.cbData).decode("utf-8")
    finally:
        ctypes.windll.kernel32.LocalFree(clear.pbData)
