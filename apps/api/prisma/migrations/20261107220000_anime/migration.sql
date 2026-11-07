-- CreateTable
CREATE TABLE "AnimeAccount" (
    "id" TEXT NOT NULL,
    "profileId" TEXT NOT NULL,
    "tracker" TEXT NOT NULL,
    "userName" TEXT NOT NULL,
    "token" TEXT NOT NULL,
    "lastSyncedAt" TIMESTAMP(3),
    "lastError" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "AnimeAccount_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "AnimeEntry" (
    "id" TEXT NOT NULL,
    "profileId" TEXT NOT NULL,
    "tracker" TEXT NOT NULL,
    "remoteId" INTEGER NOT NULL,
    "title" TEXT NOT NULL,
    "status" TEXT NOT NULL,
    "progress" INTEGER NOT NULL DEFAULT 0,
    "pushedProgress" INTEGER NOT NULL DEFAULT 0,
    "episodes" INTEGER,
    "score" DOUBLE PRECISION,
    "mediaItemId" TEXT,
    "mapped" BOOLEAN NOT NULL DEFAULT false,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "AnimeEntry_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE UNIQUE INDEX "AnimeAccount_profileId_tracker_key" ON "AnimeAccount"("profileId", "tracker");

-- CreateIndex
CREATE UNIQUE INDEX "AnimeEntry_profileId_tracker_remoteId_key" ON "AnimeEntry"("profileId", "tracker", "remoteId");

-- AddForeignKey
ALTER TABLE "AnimeAccount" ADD CONSTRAINT "AnimeAccount_profileId_fkey" FOREIGN KEY ("profileId") REFERENCES "Profile"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "AnimeEntry" ADD CONSTRAINT "AnimeEntry_profileId_fkey" FOREIGN KEY ("profileId") REFERENCES "Profile"("id") ON DELETE CASCADE ON UPDATE CASCADE;

