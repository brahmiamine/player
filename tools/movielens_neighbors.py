# Voisins « les spectateurs qui ont aimé X ont aussi aimé Y » (MovieLens ml-32m), clé = TMDB ID.
import numpy as np, scipy.sparse as sp, sys, gzip, time
d = sys.argv[1]; out = sys.argv[2]
t = time.time()
links = np.genfromtxt(f"{d}/links.csv", delimiter=",", skip_header=1, usecols=(0, 2), filling_values=-1, dtype=np.int64)
tmdb_of = dict((int(m), int(tm)) for m, tm in links if tm > 0)
r = np.loadtxt(f"{d}/ratings.csv", delimiter=",", skiprows=1, usecols=(0, 1, 2), dtype=np.float32)
print("loaded", len(r), time.time() - t, flush=True)
liked = r[r[:, 2] >= 4.0]  # « a aimé » : signal implicite robuste
users = liked[:, 0].astype(np.int64); movies = liked[:, 1].astype(np.int64)
counts = np.bincount(movies)
keep = np.array([m for m in np.unique(movies) if counts[m] >= 40 and m in tmdb_of])
col = {m: i for i, m in enumerate(keep)}
mask = np.isin(movies, keep)
u_ids, u_idx = np.unique(users[mask], return_inverse=True)
X = sp.csr_matrix((np.ones(mask.sum(), np.float32), (u_idx, np.array([col[m] for m in movies[mask]]))), shape=(len(u_ids), len(keep)))
Xt = X.T.tocsr()
n = np.asarray(X.sum(axis=0)).ravel()
print("items", len(keep), "users", len(u_ids), flush=True)
K, SHRINK = 30, 20.0
lines = []
for s in range(0, len(keep), 800):
    co = (Xt[s:s + 800] @ X).toarray()
    for i in range(co.shape[0]):
        g = s + i; co[i, g] = 0
        c = co[i]
        sim = c / np.sqrt(n[g] * n) * (c / (c + SHRINK))  # cosinus + amortissement des petits recouvrements
        top = np.argpartition(-sim, K)[:K]; top = top[np.argsort(-sim[top])]
        top = [j for j in top if sim[j] > 0.02]
        if top:
            lines.append(f"{tmdb_of[keep[g]]}:" + ",".join(str(tmdb_of[keep[j]]) for j in top))
    print(s, time.time() - t, flush=True)
with gzip.open(out, "wt") as f:
    f.write("\n".join(lines))
print("done", len(lines), time.time() - t)
