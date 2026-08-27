"""Conteúdo local de apoio: livros, personagens e perguntas bíblicas."""

from __future__ import annotations


BOOK_AUTHORS = {
    "GEN": "Tradicionalmente atribuído a Moisés", "EXO": "Tradicionalmente atribuído a Moisés",
    "LEV": "Tradicionalmente atribuído a Moisés", "NUM": "Tradicionalmente atribuído a Moisés",
    "DEU": "Tradicionalmente atribuído a Moisés", "JOS": "Tradicionalmente associado a Josué",
    "JDG": "Autor não identificado; tradição judaica associa a Samuel", "RUT": "Autor não identificado",
    "1SA": "Autor não identificado; tradições associam partes a Samuel e outros registros",
    "2SA": "Autor não identificado", "1KI": "Autor não identificado; tradição associa a Jeremias",
    "2KI": "Autor não identificado; tradição associa a Jeremias", "1CH": "Tradicionalmente associado a Esdras",
    "2CH": "Tradicionalmente associado a Esdras", "EZR": "Tradicionalmente associado a Esdras",
    "NEH": "Neemias e compiladores posteriores", "EST": "Autor não identificado",
    "JOB": "Autor não identificado", "PSA": "Coleção de Davi, Asafe, filhos de Corá e outros",
    "PRO": "Principalmente Salomão, com coleções de outros sábios", "ECC": "O Pregador; tradicionalmente associado a Salomão",
    "SNG": "Tradicionalmente associado a Salomão", "ISA": "Isaías",
    "JER": "Jeremias", "LAM": "Tradicionalmente associado a Jeremias", "EZK": "Ezequiel",
    "DAN": "Daniel", "HOS": "Oseias", "JOL": "Joel", "AMO": "Amós", "OBA": "Obadias",
    "JON": "Jonas", "MIC": "Miqueias", "NAM": "Naum", "HAB": "Habacuque", "ZEP": "Sofonias",
    "HAG": "Ageu", "ZEC": "Zacarias", "MAL": "Malaquias",
    "MAT": "Tradicionalmente atribuído a Mateus", "MRK": "Tradicionalmente atribuído a Marcos",
    "LUK": "Tradicionalmente atribuído a Lucas", "JHN": "Tradicionalmente atribuído a João",
    "ACT": "Tradicionalmente atribuído a Lucas", "ROM": "Paulo", "1CO": "Paulo", "2CO": "Paulo",
    "GAL": "Paulo", "EPH": "Tradicionalmente atribuído a Paulo", "PHP": "Paulo", "COL": "Paulo",
    "1TH": "Paulo", "2TH": "Paulo", "1TI": "Tradicionalmente atribuído a Paulo",
    "2TI": "Tradicionalmente atribuído a Paulo", "TIT": "Tradicionalmente atribuído a Paulo",
    "PHM": "Paulo", "HEB": "Autor não identificado; várias propostas existem desde a antiguidade",
    "JAS": "Tradicionalmente atribuído a Tiago, irmão de Jesus", "1PE": "Pedro", "2PE": "Tradicionalmente atribuído a Pedro",
    "1JN": "Tradicionalmente atribuído a João", "2JN": "Tradicionalmente atribuído a João",
    "3JN": "Tradicionalmente atribuído a João", "JUD": "Judas, irmão de Tiago",
    "REV": "João; tradicionalmente identificado com o apóstolo João",
}

BOOK_GROUPS = (
    (range(1, 6), "Pentateuco", "As origens, a aliança, o êxodo e a formação de Israel.", "Israel e as comunidades que preservaram a Lei"),
    (range(6, 18), "Livros históricos", "A história de Israel na terra, monarquia, exílio e retorno.", "O povo de Israel"),
    (range(18, 23), "Poesia e sabedoria", "Oração, sofrimento, culto e sabedoria para a vida.", "Adoradores e aprendizes de sabedoria"),
    (range(23, 40), "Profetas", "Chamado à fidelidade, justiça, esperança e restauração.", "Israel e Judá em diferentes crises históricas"),
    (range(40, 44), "Evangelhos", "A vida, os ensinos, a morte e a ressurreição de Jesus.", "Comunidades cristãs e pessoas interessadas em conhecer Jesus"),
    (range(44, 45), "História da igreja", "A expansão do testemunho cristão após a ressurreição.", "Comunidades cristãs do primeiro século"),
    (range(45, 66), "Cartas", "Ensino, encorajamento e orientação para comunidades e líderes cristãos.", "Igrejas e cristãos do primeiro século"),
    (range(66, 67), "Profecia apocalíptica", "Esperança e perseverança diante do sofrimento, sob o senhorio de Deus.", "Sete igrejas da Ásia e leitores cristãos"),
)


def book_information(book: dict, chapter_count: int) -> dict:
    """Monta informação cuidadosa, distinguindo tradição de certeza histórica."""
    number = int(book["book_number"])
    group, context, audience = next(
        (name, description, readers) for numbers, name, description, readers in BOOK_GROUPS if number in numbers
    )
    period = "Antiguidade israelita; a data exata de composição é discutida"
    if number >= 40:
        period = "Século 1 d.C.; a data exata varia conforme o livro e a posição adotada"
    return {
        "name": book["book_name"],
        "author": BOOK_AUTHORS.get(book["book_code"], "Autor não identificado"),
        "period": period,
        "context": context,
        "audience": audience,
        "theme": group,
        "summary": f"{book['book_name']} integra o grupo {group.lower()} e contribui para {context.lower()}",
        "chapters": chapter_count,
    }


CHARACTERS = {
    "Jesus": ("Centro dos Evangelhos e da fé cristã.", "Nascimento, ministério, ensinos, cruz e ressurreição.", ("Mateus 1", "Marcos 1", "Lucas 2", "João 20")),
    "Pedro": ("Discípulo de Jesus e liderança da igreja nascente.", "Chamado, confissão, negação, restauração e testemunho.", ("Mateus 16", "João 21", "Atos 2")),
    "Paulo": ("Missionário e autor de cartas do Novo Testamento.", "Conversão, viagens missionárias, prisões e ensino às igrejas.", ("Atos 9", "Atos 13", "Romanos 1")),
    "Davi": ("Pastor, guerreiro, rei e salmista de Israel.", "Unção, confronto com Golias, reinado, quedas e arrependimento.", ("1 Samuel 16", "1 Samuel 17", "2 Samuel 7", "Salmos 51")),
    "Moisés": ("Líder do êxodo e mediador da Lei.", "Chamado, libertação do Egito, Sinai e caminhada no deserto.", ("Êxodo 3", "Êxodo 12", "Êxodo 20")),
    "Abraão": ("Patriarca chamado a confiar na promessa de Deus.", "Chamado, aliança, nascimento de Isaque e prova de fé.", ("Gênesis 12", "Gênesis 15", "Gênesis 22")),
    "José": ("Filho de Jacó que se tornou governador no Egito.", "Sonhos, escravidão, prisão, governo e reconciliação.", ("Gênesis 37", "Gênesis 41", "Gênesis 45")),
    "Maria": ("Mãe de Jesus e discípula presente na história dos Evangelhos.", "Anunciação, nascimento de Jesus e presença entre os discípulos.", ("Lucas 1", "Lucas 2", "Atos 1")),
    "Ester": ("Rainha que intercedeu por seu povo.", "Ascensão à realeza, risco pessoal e livramento dos judeus.", ("Ester 2", "Ester 4", "Ester 7")),
    "Rute": ("Moabita marcada por lealdade e fé.", "Perda, compromisso com Noemi, encontro com Boaz e nova família.", ("Rute 1", "Rute 2", "Rute 4")),
    "Daniel": ("Judeu exilado que serviu em cortes estrangeiras.", "Fidelidade, interpretação de sonhos, livramento e visões.", ("Daniel 1", "Daniel 3", "Daniel 6")),
    "Elias": ("Profeta do reino do Norte.", "Confronto com Acabe, Carmelo, desânimo e sucessão profética.", ("1 Reis 17", "1 Reis 18", "2 Reis 2")),
}


QUIZ = (
    # Antigo Testamento
    ("fácil", "Antigo Testamento", "Quem construiu a arca?", ("Moisés", "Noé", "Davi", "Paulo"), 1, "Noé obedeceu à ordem de construir a arca.", "Gênesis 6"),
    ("fácil", "Antigo Testamento", "Qual mar se abriu para Israel sair do Egito?", ("Mar Morto", "Mar da Galileia", "Mar Vermelho", "Mediterrâneo"), 2, "Israel atravessou o mar em terra seca.", "Êxodo 14"),
    ("médio", "Antigo Testamento", "Qual profeta confrontou os profetas de Baal no Carmelo?", ("Isaías", "Elias", "Eliseu", "Jeremias"), 1, "Elias convocou o confronto no monte Carmelo.", "1 Reis 18"),
    ("médio", "Antigo Testamento", "Quem sucedeu Moisés na liderança de Israel?", ("Calebe", "Arão", "Josué", "Samuel"), 2, "Josué conduziu o povo na entrada em Canaã.", "Josué 1"),
    ("difícil", "Antigo Testamento", "Qual rei encontrou o Livro da Lei durante reformas no templo?", ("Ezequias", "Acaz", "Josias", "Jeroboão"), 2, "O livro foi encontrado durante o reinado de Josias.", "2 Reis 22"),
    ("difícil", "Antigo Testamento", "Qual profeta teve a visão de um vale de ossos secos?", ("Daniel", "Ezequiel", "Amós", "Oséias"), 1, "Ezequiel viu os ossos receberem vida na visão.", "Ezequiel 37"),
    # Novo Testamento
    ("fácil", "Novo Testamento", "Quem batizou Jesus?", ("Pedro", "João Batista", "Tiago", "André"), 1, "João batizou Jesus no Jordão.", "Mateus 3"),
    ("fácil", "Novo Testamento", "Qual apóstolo negou Jesus três vezes?", ("João", "Tomé", "Pedro", "Filipe"), 2, "Pedro negou conhecer Jesus e depois se arrependeu.", "Lucas 22:54-62"),
    ("médio", "Novo Testamento", "Quem foi escolhido para ocupar o lugar de Judas?", ("Barnabé", "Matias", "Silas", "Marcos"), 1, "Matias foi contado com os onze apóstolos.", "Atos 1:26"),
    ("médio", "Novo Testamento", "Em qual ilha João recebeu a visão do Apocalipse?", ("Creta", "Chipre", "Malta", "Patmos"), 3, "João estava na ilha de Patmos.", "Apocalipse 1:9"),
    ("difícil", "Novo Testamento", "A quem foi dirigida a carta pessoal sobre Onésimo?", ("Tito", "Timóteo", "Filemom", "Silas"), 2, "Paulo escreveu a Filemom a respeito de Onésimo.", "Filemom 1"),
    ("difícil", "Novo Testamento", "Quem caiu da janela enquanto Paulo falava?", ("Êutico", "Trófimo", "Aristarco", "Ágabo"), 0, "Êutico caiu do terceiro andar e foi socorrido por Paulo.", "Atos 20:7-12"),
    # Jesus
    ("fácil", "Jesus", "Em qual cidade Jesus nasceu?", ("Nazaré", "Jerusalém", "Belém", "Roma"), 2, "Os relatos do nascimento situam Jesus em Belém.", "Mateus 2:1"),
    ("fácil", "Jesus", "Quantos apóstolos Jesus escolheu?", ("Sete", "Dez", "Doze", "Quarenta"), 2, "Jesus chamou doze para estarem com ele.", "Marcos 3:13-19"),
    ("médio", "Jesus", "Qual foi o primeiro sinal de Jesus narrado por João?", ("Multiplicação dos pães", "Água transformada em vinho", "Cura de um cego", "Ressurreição de Lázaro"), 1, "O sinal ocorreu numa festa de casamento em Caná.", "João 2:1-11"),
    ("médio", "Jesus", "Quem Jesus chamou para sair do túmulo?", ("Jairo", "Bartimeu", "Lázaro", "Zaqueu"), 2, "Jesus chamou Lázaro depois de quatro dias.", "João 11:43"),
    ("difícil", "Jesus", "Em qual aldeia Jesus encontrou dois discípulos após ressuscitar?", ("Betânia", "Emaús", "Caná", "Naim"), 1, "Dois discípulos caminhavam para Emaús.", "Lucas 24:13-35"),
    ("difícil", "Jesus", "Qual discípulo perguntou: Senhor, mostra-nos o Pai?", ("Filipe", "André", "Mateus", "Tiago"), 0, "Filipe fez a pergunta durante o ensino de Jesus.", "João 14:8"),
    # Personagens
    ("fácil", "personagens", "Quem enfrentou Golias?", ("Saul", "Samuel", "Davi", "Salomão"), 2, "Davi enfrentou o guerreiro filisteu.", "1 Samuel 17"),
    ("fácil", "personagens", "Quem foi lançado numa cova de leões?", ("Daniel", "José", "Elias", "Neemias"), 0, "Daniel foi preservado na cova dos leões.", "Daniel 6"),
    ("médio", "personagens", "Quem interpretou sonhos no Egito e se tornou governador?", ("Daniel", "José", "Neemias", "Esdras"), 1, "José interpretou os sonhos de Faraó.", "Gênesis 41"),
    ("médio", "personagens", "Qual mulher se tornou rainha e intercedeu por seu povo?", ("Rute", "Débora", "Ester", "Miriã"), 2, "Ester arriscou a vida ao procurar o rei.", "Ester 4-7"),
    ("difícil", "personagens", "Quem ajudou Paulo e Silas a sair da prisão em Filipos?", ("Um terremoto", "Barnabé", "Um anjo", "O governador"), 0, "Um terremoto abriu as portas e soltou as correntes.", "Atos 16:25-26"),
    ("difícil", "personagens", "Qual juiz fez um voto precipitado envolvendo sua filha?", ("Gideão", "Sansão", "Jefté", "Otniel"), 2, "Jefté fez um voto antes da batalha contra os amonitas.", "Juízes 11"),
    # Livros da Bíblia
    ("fácil", "livros da Bíblia", "Qual é o primeiro livro da Bíblia?", ("Êxodo", "Gênesis", "Salmos", "Mateus"), 1, "Gênesis abre a ordem bíblica comum.", "Gênesis 1"),
    ("fácil", "livros da Bíblia", "Qual é o último livro do Novo Testamento?", ("Judas", "Hebreus", "Apocalipse", "Atos"), 2, "Apocalipse encerra o cânon do Novo Testamento.", "Apocalipse 1"),
    ("médio", "livros da Bíblia", "Qual livro vem depois de Atos?", ("Hebreus", "Romanos", "João", "Tiago"), 1, "Romanos segue Atos na ordem canônica comum.", "Romanos 1"),
    ("médio", "livros da Bíblia", "Quantos Evangelhos há no Novo Testamento?", ("Três", "Quatro", "Cinco", "Doze"), 1, "Mateus, Marcos, Lucas e João são os quatro Evangelhos.", "Mateus 1"),
    ("difícil", "livros da Bíblia", "Qual livro do Antigo Testamento não menciona explicitamente o nome de Deus?", ("Ester", "Rute", "Neemias", "Cantares"), 0, "Ester narra a preservação dos judeus sem mencionar explicitamente o nome de Deus.", "Ester 1"),
    ("difícil", "livros da Bíblia", "Qual é a carta mais curta de Paulo em número de versículos?", ("Tito", "Filemom", "2 Timóteo", "Gálatas"), 1, "Filemom possui apenas um capítulo e vinte e cinco versículos.", "Filemom 1"),
    # Perguntas gerais
    ("fácil", "perguntas gerais", "Qual oração Jesus ensinou como modelo aos discípulos?", ("O Pai Nosso", "O cântico de Maria", "O Salmo 23", "A oração de Jabez"), 0, "Jesus apresentou um modelo conhecido como Pai Nosso.", "Mateus 6:9-13"),
    ("fácil", "perguntas gerais", "Qual é o maior mandamento segundo Jesus?", ("Amar a Deus", "Guardar o sábado", "Jejuar", "Dar ofertas"), 0, "Jesus ensinou a amar a Deus de todo o coração.", "Mateus 22:36-38"),
    ("médio", "perguntas gerais", "Em qual cidade os discípulos foram chamados cristãos pela primeira vez?", ("Éfeso", "Corinto", "Antioquia", "Jerusalém"), 2, "O nome aparece ligado à comunidade de Antioquia.", "Atos 11:26"),
    ("médio", "perguntas gerais", "Qual capítulo é conhecido como o capítulo do amor?", ("Romanos 8", "1 Coríntios 13", "Hebreus 11", "Salmos 119"), 1, "Paulo descreve a primazia e as características do amor.", "1 Coríntios 13"),
    ("difícil", "perguntas gerais", "Qual casal morreu após mentir sobre o valor de uma propriedade?", ("Áquila e Priscila", "Herodes e Herodias", "Ananias e Safira", "Félix e Drusila"), 2, "Ananias e Safira mentiram sobre a oferta apresentada.", "Atos 5:1-11"),
    ("difícil", "perguntas gerais", "Qual igreja do Apocalipse foi chamada de morna?", ("Éfeso", "Esmirna", "Filadélfia", "Laodiceia"), 3, "A mensagem a Laodiceia repreende sua condição morna.", "Apocalipse 3:14-22"),
)
