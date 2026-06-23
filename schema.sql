-- Generated primary keys (person_id, organisation_id, comment_id start at their project_id*1_000_000)

-- 1. Project Table
CREATE TABLE Project (
                         project_id INTEGER PRIMARY KEY, -- pre-allocated in the table above
                         project_name TEXT NOT NULL,
                         enhancement_proposal_name TEXT NOT NULL,
                         copyright TEXT NOT NULL
);

-- 2. Person & Organisation
CREATE TABLE Person (
                        person_id INTEGER PRIMARY KEY,
                        full_name TEXT
);
CREATE TABLE PersonIdentifier (
                                  person_id INTEGER NOT NULL,
                                  domain TEXT, -- eg “github.com”, “git_author” (where was the ID sourced from?)
                                  identifier_type TEXT, -- eg “email”, “username”, “display_name” (what kind of ID is it?)
                                  identifier TEXT, -- e.g. “stijn@example.com”, in case of multiple identifiers for the same domain+identifier_type, put multiple rows with different identifier, as it's part of the primary key
                                  PRIMARY KEY (person_id, domain, identifier_type, identifier),
                                  FOREIGN KEY (person_id) REFERENCES Person(person_id)
);
CREATE TABLE Organisation (
                              organisation_id INTEGER PRIMARY KEY,
                              organisation_name TEXT NOT NULL
);
CREATE TABLE Affiliation (
                             organisation_id INTEGER,
                             person_id INTEGER,
                             PRIMARY KEY (organisation_id, person_id),
                             FOREIGN KEY (organisation_id) REFERENCES Organisation(organisation_id),
                             FOREIGN KEY (person_id) REFERENCES Person(person_id)
);

-- 3. Proposal Table
CREATE TABLE Proposal (
                          project_id INTEGER,
                          proposal_id TEXT,
                          topic TEXT,
                          proposal_type TEXT,
                          PRIMARY KEY (project_id, proposal_id),
                          FOREIGN KEY (project_id) REFERENCES Project(project_id)
);

-- 4. Revisions & Authorship
CREATE TABLE ProposalStatus (
                                project_id INTEGER,
                                proposal_id TEXT,
                                status_index INTEGER, -- status_index increments on raw_status change
                                raw_status TEXT NOT NULL,
                                normalised_status TEXT NOT NULL CHECK (
                                    normalised_status IN (
                                                          'accepted', -- Ready to be implemented (or already implemented)
                                                          'rejected', -- Not going to be implemented
                                                          'draft', -- Incomplete
                                                          'review', -- Complete, waiting for review
                                                          'withdrawn', -- Withdrawn by proposal author
                                                          'superseded',  -- Outdated, replaced by another proposal
                                                          'unknown'
                                        )
                                    ),
                                created_at DATETIME NOT NULL CHECK (datetime(created_at) IS NOT NULL),
                                PRIMARY KEY (project_id, proposal_id, status_index),
                                FOREIGN KEY (project_id, proposal_id) REFERENCES Proposal(project_id, proposal_id)
);
CREATE TABLE ProposalRevision (
                                  project_id INTEGER,
                                  proposal_id TEXT,
                                  revision_index INTEGER,
                                  title TEXT NOT NULL,
                                  created_at DATETIME NOT NULL CHECK (datetime(created_at) IS NOT NULL),
                                  content TEXT,
                                  implemented_at_version TEXT,
                                  PRIMARY KEY (project_id, proposal_id, revision_index),
                                  FOREIGN KEY (project_id, proposal_id) REFERENCES Proposal(project_id, proposal_id)
);
CREATE TABLE ProposalRevisionAuthor (
                                        project_id INTEGER,
                                        proposal_id TEXT,
                                        revision_index INTEGER,
-- multiple rows with the same project_id, proposal_id and revision_index but different author_ids means multiple authors at some revision
                                        author_id INTEGER,
                                        PRIMARY KEY (
                                                     project_id,
                                                     proposal_id,
                                                     revision_index,
                                                     author_id
                                            ),
                                        FOREIGN KEY (author_id) REFERENCES Person(person_id),
                                        FOREIGN KEY (project_id, proposal_id, revision_index) REFERENCES ProposalRevision(project_id, proposal_id, revision_index)
);

-- 5. Relationships & Interactions
CREATE TABLE RelatedProposal (
                                 project_id INTEGER,
                                 proposal_id TEXT,
                                 related_project_id INTEGER,
                                 related_proposal_id TEXT,
                                 type TEXT CHECK (type IN ('related', 'supersedes')),
                                 PRIMARY KEY (
                                              project_id,
                                              proposal_id,
                                              related_project_id,
                                              related_proposal_id
                                     ),
                                 FOREIGN KEY (project_id, proposal_id) REFERENCES Proposal(project_id, proposal_id),
                                 FOREIGN KEY (related_project_id, related_proposal_id) REFERENCES Proposal(project_id, proposal_id)
);
CREATE TABLE Comment (
                         comment_id INTEGER PRIMARY KEY,
                         author_id INTEGER NOT NULL,
                         project_id INTEGER NOT NULL,
                         proposal_id TEXT NOT NULL,
                         comment_on_comment_id INTEGER, -- id of parent comment, null if root comment (discussion, issue, pr, etc.)
                         created_at DATETIME,
                         content TEXT NOT NULL,
                         FOREIGN KEY (author_id) REFERENCES Person(person_id),
                         FOREIGN KEY (comment_on_comment_id) REFERENCES Comment(comment_id),
                         FOREIGN KEY (project_id, proposal_id) REFERENCES Proposal(project_id, proposal_id)
);
