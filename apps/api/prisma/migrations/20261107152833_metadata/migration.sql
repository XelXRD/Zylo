-- CreateEnum
CREATE TYPE "MediaType" AS ENUM ('movie', 'series', 'season', 'episode');

-- CreateTable
CREATE TABLE "Integration" (
    "id" TEXT NOT NULL,
    "secret" TEXT NOT NULL,
    "settings" JSONB NOT NULL DEFAULT '{}',
    "lastCheckedAt" TIMESTAMP(3),
    "lastError" TEXT,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "Integration_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "MediaItem" (
    "id" TEXT NOT NULL,
    "type" "MediaType" NOT NULL,
    "parentId" TEXT,
    "title" TEXT NOT NULL,
    "originalTitle" TEXT,
    "overview" TEXT,
    "year" INTEGER,
    "releaseDate" TEXT,
    "runtime" INTEGER,
    "posterPath" TEXT,
    "backdropPath" TEXT,
    "rating" DOUBLE PRECISION,
    "seasonNumber" INTEGER,
    "episodeNumber" INTEGER,
    "data" JSONB NOT NULL DEFAULT '{}',
    "language" TEXT NOT NULL,
    "refreshedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "MediaItem_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "ExternalId" (
    "mediaItemId" TEXT NOT NULL,
    "system" TEXT NOT NULL,
    "type" "MediaType" NOT NULL,
    "value" TEXT NOT NULL,

    CONSTRAINT "ExternalId_pkey" PRIMARY KEY ("system","type","value")
);

-- CreateIndex
CREATE INDEX "MediaItem_parentId_idx" ON "MediaItem"("parentId");

-- CreateIndex
CREATE INDEX "MediaItem_refreshedAt_idx" ON "MediaItem"("refreshedAt");

-- CreateIndex
CREATE INDEX "ExternalId_mediaItemId_idx" ON "ExternalId"("mediaItemId");

-- AddForeignKey
ALTER TABLE "MediaItem" ADD CONSTRAINT "MediaItem_parentId_fkey" FOREIGN KEY ("parentId") REFERENCES "MediaItem"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "ExternalId" ADD CONSTRAINT "ExternalId_mediaItemId_fkey" FOREIGN KEY ("mediaItemId") REFERENCES "MediaItem"("id") ON DELETE CASCADE ON UPDATE CASCADE;
